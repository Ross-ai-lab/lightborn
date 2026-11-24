"""Prepare the owner's whole music tracks and default resource packs before the game starts."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, zipfile, re, tomllib

ROOT=Path(__file__).resolve().parent.parent
FOLDERS={'hearth':'01 Hearth','wilds':'02 Wilds by day','night':'03 Night','stirring':'04 Stirring',
 'hunt':'05 Hunt','boss':'06 Boss','nether':'07 Nether','end':'08 End','underground':'09 Underground',
 'dragon':'10 The Dragon','finale':'11 Finale'}
FORMATS={'.ogg','.mp3','.m4a','.aac','.flac','.wav','.opus','.webm','.mp4'}

def digest_file(path,algorithm):
 with path.open('rb') as file:return hashlib.file_digest(file,algorithm).hexdigest()

def write_json(path,value):
 path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def enable_packs(game,names):
 path=game/'options.txt';lines=path.read_text(encoding='utf-8').splitlines() if path.exists() else []
 current=[]
 for line in lines:
  if line.startswith('resourcePacks:'):current=json.loads(line.split(':',1)[1])
 for name in names:
  if name not in current:current.append(name)
 lines=[line for line in lines if not line.startswith('resourcePacks:')]
 lines.append('resourcePacks:'+json.dumps(current,ensure_ascii=False,separators=(',',':')))
 path.write_text('\n'.join(lines)+'\n',encoding='utf-8')

def vorbis(path):
 with path.open('rb') as stream:header=stream.read(8192)
 pos=header.find(b'\x01vorbis')
 return header.startswith(b'OggS') and pos>=0 and len(header)>pos+11 and header[pos+11]<=2

def toml_table_drop(text,table):
 # Drop a [table] block (header to the next header): the inline key written in its place replaces it.
 start=re.search(r'(?m)^[ \t]*\['+re.escape(table)+r'\][ \t]*\r?$',text)
 if not start:return text
 end=re.search(r'(?m)^[ \t]*\[',text[start.end():])
 return text[:start.start()]+(text[start.end()+end.start():] if end else '')

def toml_settings(path,section,values):
 text=path.read_text(encoding='utf-8') if path.exists() else ''
 # A mod re-saving its config turns an inline table into a [section.key] block; writing the inline key beside that
 # block declares it twice and the file stops parsing, so the block goes first.
 for key in values:text=toml_table_drop(text,section+'.'+key)
 pattern=r'(?m)^\s*\['+re.escape(section)+r'\]\s*$' if section else r'\A'
 start=re.search(pattern,text)
 if start:
  end=re.search(r'(?m)^\s*\[',text[start.end():]);stop=start.end()+end.start() if end else len(text);body=text[start.end():stop]
  for key,value in values.items():
   line=key+' = '+value
   if re.search(r'(?m)^\s*'+re.escape(key)+r'\s*=',body):body=re.sub(r'(?m)^\s*'+re.escape(key)+r'\s*=.*$',lambda m:line,body)  # noqa: B023 (re.sub calls it at once)
   else:body+='\n'+line+'\n'
  text=text[:start.end()]+body+text[stop:]
 else:text+='\n['+section+']\n'+'\n'.join(key+' = '+value for key,value in values.items())+'\n'
 path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8')

def prepare(game,music):
 game.mkdir(parents=True,exist_ok=True);packs=game/'resourcepacks';packs.mkdir(exist_ok=True)
 enabled=[]
 border_path=game/'config/itemborders.toml'
 border_text=border_path.read_text(encoding='utf-8') if border_path.exists() else ''
 if re.search(r'(?m)^[ \t]*manual_borders[ \t]*=',border_text) and re.search(r'(?m)^[ \t]*\[client\.options\.manual_borders\]',border_text):
  # Heal a file that already declares it twice: keep the block Item Borders itself saved.
  border_text=re.sub(r'(?m)^[ \t]*manual_borders[ \t]*=.*\r?\n?','',border_text);border_path.write_text(border_text,encoding='utf-8')
 borders=tomllib.loads(border_text).get('client',{}).get('options',{}).get('manual_borders',{}) if border_path.exists() else {}
 for color,rarity in zip(('#FFFFFF','#5555FF','#FFFF55','#FFAA00','#FF5555'),('PLAIN','MAGIC','RARE','LEGENDARY','MYTHIC')):borders[color]=['&lightborn_rarity='+json.dumps(rarity)]
 manual='{ '+', '.join(json.dumps(color)+' = '+json.dumps(selectors) for color,selectors in borders.items())+' }'
 toml_settings(border_path,'client.options',{'show_for_common':'true','legendary_tooltips_sync':'true','manual_borders':manual})
 # Terralith's welcome chat line is off (owner, 2026-11-16); its other settings stay as the player left them.
 terra_path=game/'config/terralith.json'
 # Terralith 2.6.5 discards a file without config_version and rewrites its defaults, so a fresh file is written whole.
 terra=json.loads(terra_path.read_text(encoding='utf-8')) if terra_path.exists() else {'config_version':1,'modules':{
  'custom_structures':True,'fog_tweaks':True,'intro_message':False,'recipe_changes':False,'skylands':True,'terrain_slabs':True,'vanilla_stone_gen':False}}
 terra.setdefault('config_version',1);terra.setdefault('modules',{})['intro_message']=False;write_json(terra_path,terra)
 lock=json.loads((ROOT/'tools/companion-mods.lock.json').read_text(encoding='utf-8'))
 for pin in lock:
  if not pin.get('resource_pack'):continue
  source=ROOT/'companion-mods'/pin['file'];target=packs/pin['file']
  if source.exists():
   if digest_file(source,'sha512')!=pin['sha512']:raise ValueError('Resource pack checksum mismatch: '+pin['file'])
   if not target.exists() or digest_file(target,'sha512')!=pin['sha512']:shutil.copy2(source,target)
  if target.exists():enabled.append('file/'+pin['file'])
 out=packs/'Lightborn-Music';assets=out/'assets/lightborn';events={};counts={};used=set()
 ffmpeg=shutil.which('ffmpeg')
 for mood,folder in FOLDERS.items():
  source_dir=music/folder;source_dir.mkdir(parents=True,exist_ok=True);sounds=[]
  for source in sorted(source_dir.rglob('*')):
   if not source.is_file() or source.suffix.lower() not in FORMATS:continue
   digest=digest_file(source,'sha256');relative=f'music/{mood}/{digest}.ogg';target=assets/'sounds'/relative
   target.parent.mkdir(parents=True,exist_ok=True)
   if not target.exists():
    if vorbis(source):
     try:os.link(source,target)
     except OSError:shutil.copy2(source,target)
    else:
     if not ffmpeg:raise RuntimeError('FFmpeg is required to convert this whole track: '+str(source))
     command=[ffmpeg,'-hide_banner','-loglevel','error','-nostdin','-y','-i',str(source),'-vn','-ac','2','-c:a','libvorbis','-q:a','6',str(target)]
     result=subprocess.run(command,capture_output=True,text=True)
     if result.returncode:target.unlink(missing_ok=True);raise RuntimeError('Could not convert '+str(source)+': '+result.stderr[-500:])
   used.add(target.resolve());sounds.append({'name':'lightborn:'+relative[:-4],'stream':True})
  events['music.'+mood]={'sounds':sounds};counts[mood]=len(sounds)
 # Remove only generated, content-addressed audio belonging to this pack, never the source tracks.
 for stale in (assets/'sounds').glob('music/*/*.ogg'):
  if stale.resolve() not in used and len(stale.stem)==64:stale.unlink()
 write_json(assets/'sounds.json',events)
 write_json(out/'pack.mcmeta',{'pack':{'description':'Lightborn situational soundtrack','min_format':[97,1],'max_format':[97,1]}})
 write_json(out/'track-counts.json',counts);enabled.append('file/Lightborn-Music');enable_packs(game,enabled)
 print('Music: '+str(sum(counts.values()))+' whole tracks. Empty situations keep vanilla music.')
 return counts

if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--game-dir',type=Path,default=ROOT/'Game');parser.add_argument('--music-dir',type=Path,default=ROOT/'Music');args=parser.parse_args();prepare(args.game_dir,args.music_dir)
