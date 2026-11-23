"""Draw the Light gifts, the Scribe's robe and their model definitions. No downloaded art."""
from pathlib import Path
import json, random
from PIL import Image, ImageDraw
ROOT=Path(__file__).resolve().parent.parent
A=ROOT/'src/main/resources/assets/lightborn'
IVORY='#fff1cf'; GOLD='#d4ad50'; TEAL='#48dad6'; SHADE='#857351'; EDGE='#3b3542'
def write(path,obj):
 f=A/path;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(json.dumps(obj,indent=2)+'\n',encoding='utf-8')
def save(img,path):
 f=A/path;f.parent.mkdir(parents=True,exist_ok=True);img.save(f)
# The Light gear's art is tools/make_light_gear.py; this draws the story keepsakes only.
def light_key():
 """The Light Key (owner, 2026-11-16, replacing the First Rune): an ivory-and-gold key, bow top right round a teal
 gem, shaft falling to the bit bottom left. Shapes are laid on a mask, then outlined dark and bevel-lit from the top
 left, like the Light gear."""
 mask=Image.new('L',(32,32),0);m=ImageDraw.Draw(mask)
 m.ellipse((15,2,29,16),fill=255);m.ellipse((19,6,25,12),fill=0)  # the bow, a ring
 m.line([(17,14),(6,25)],fill=255,width=4)  # the shaft
 m.line([(9,23),(13,27)],fill=255,width=3);m.line([(12,20),(15,23)],fill=255,width=2)  # the bit's two teeth
 m.rectangle((16,12,18,14),fill=255)  # collar where shaft meets bow
 im=Image.new('RGBA',(32,32),(0,0,0,0));px=im.load();mk=mask.load()
 inside=lambda x,y:0<=x<32 and 0<=y<32 and mk[x,y]>0
 LIGHT='#fbe6a8';MID='#d9b45a';DEEP='#a47e34';DARK='#6e5124'
 for y in range(32):
  for x in range(32):
   if inside(x,y):
    lit=not inside(x-1,y) or not inside(x,y-1);shade=not inside(x+1,y) or not inside(x,y+1)
    px[x,y]=Image.new('RGBA',(1,1),LIGHT if lit and not shade else DEEP if shade and not lit else MID).getpixel((0,0))
   elif any(inside(x+dx,y+dy) for dx,dy in ((1,0),(-1,0),(0,1),(0,-1))):px[x,y]=Image.new('RGBA',(1,1),EDGE).getpixel((0,0))
 d=ImageDraw.Draw(im)
 d.line([(16,13),(8,21)],fill=IVORY,width=1)  # ivory inlay down the shaft
 d.arc((16,3,28,15),200,300,fill=IVORY,width=1)  # sheen on the bow's upper left
 d.arc((16,3,28,15),20,110,fill=DARK,width=1)  # its lower right in shadow
 d.ellipse((20,7,24,11),fill=EDGE);d.ellipse((21,8,23,10),fill=TEAL);d.point((21,8),fill='#e8fffd')  # the gem
 d.point((27,3),fill=IVORY);d.point((28,2),fill='#fff8e6');d.point((29,3),fill=IVORY);d.point((28,4),fill=IVORY)  # a glint
 return im
for name in ['dragon_scale','light_key','scribe_seal']:
 im=Image.new('RGBA',(32,32));d=ImageDraw.Draw(im)
 if name=='dragon_scale':
  d.polygon([(16,2),(27,12),(24,23),(16,30),(8,23),(5,12)],fill=EDGE);d.polygon([(16,4),(25,13),(22,22),(16,27),(10,22),(7,13)],fill=GOLD);d.polygon([(16,7),(22,14),(20,21),(16,24),(12,21),(10,14)],fill=IVORY);d.line([(16,9),(13,16),(17,15),(15,22)],fill=TEAL,width=2)
 elif name=='light_key':im=light_key()
 else:
  d.rounded_rectangle((6,3,26,29),radius=3,fill=EDGE);d.rectangle((8,5,24,27),fill='#392a50');d.rectangle((10,7,22,25),fill='#130f22');d.line([(14,9),(19,12),(13,16),(19,20),(15,23)],fill='#e3baff',width=2)
 save(im,f'textures/item/{name}.png');write(f'models/item/{name}.json',{'parent':'minecraft:item/generated','textures':{'layer0':f'lightborn:item/{name}'}});write(f'items/{name}.json',{'model':{'type':'minecraft:model','model':f'lightborn:item/{name}'}})
# The worn Light armour is drawn by tools/make_light_gear.py.
write('equipment/light.json',{'layers':{k:[{'texture':'lightborn:light'}] for k in ['humanoid','humanoid_leggings']}})
im=Image.new('RGBA',(64,64),'#13101c');d=ImageDraw.Draw(im);rng=random.Random(71)
for i in range(650):
 x,y=rng.randrange(64),rng.randrange(64);d.point((x,y),fill=rng.choice(['#211b2c','#17131f','#2a2335']))
for x in [19,24,29,34]:
 for y in [18,25,32]:d.line([(x,y),(x+2,y+2),(x,y+4)],fill='#ad82d5',width=1)
d.rectangle((9,9,17,16),fill='#08070d');d.rectangle((10,12,11,12),fill='#87e5e3');d.rectangle((14,12,15,12),fill='#87e5e3')
save(im,'textures/entity/last_scribe.png')
lang=A/'lang/en_us.json';j=json.loads(lang.read_text(encoding='utf-8'));j.pop('item.lightborn.first_rune',None);j.update({f'item.lightborn.{k}':v for k,v in {'light_helm':'Light Helm','light_chestplate':'Light Chestplate','light_greaves':'Light Greaves','light_boots':'Light Boots','light_sword':'Light Sword','light_staff':'Light Staff','dragon_scale':"The Dragon's Scale",'light_key':'The Light Key','scribe_seal':"The Last Scribe's Seal"}.items()});j.update({'entity.lightborn.last_scribe':'Orvane, the Last Scribe','key.lightborn.story_power':'Call Dragon / Dragon Form','key.lightborn.dragon_breath':'Dragon Light Breath'});lang.write_text(json.dumps(j,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Drew nine story items, two armour layers and the Scribe; wrote models and names.')
