"""Eight single-design furnishings: original block geometry, vanilla materials, recipes and drops."""
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parent.parent
A=ROOT/'src/main/resources/assets/lightborn';D=ROOT/'src/main/resources/data/lightborn'
def write(base,path,data):
 f=base/path;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
def box(a,b,t='oak'):
 dx,dy,dz=[min(16,b[i]-a[i]) for i in range(3)]
 return {'from':a,'to':b,'faces':{side:{'texture':'#'+t,'uv':[0,0,dx if side in ['north','south','up','down'] else dz,dz if side in ['up','down'] else dy]} for side in ['north','south','east','west','up','down']}}
legs=[box([x,0,z],[x+2,8,z+2]) for x in [1,13] for z in [1,13]]
models={
'chair':legs+[box([0,7,0],[16,9,16]),box([1,9,12],[15,20,15]),box([2,9,2],[14,10,12],'teal'),box([3,18,11],[13,20,16],'ivory')],
'table':[box([0,11,0],[16,13,16],'ivory')]+[box([x,0,z],[x+2,11,z+2]) for x in [1,13] for z in [1,13]]+[box([2,10,2],[14,11,14],'teal')],
'couch':[box([0,0,1],[16,3,15]),box([1,3,2],[15,8,13],'teal'),box([0,3,12],[16,15,16],'ivory'),box([0,3,1],[3,12,13],'ivory'),box([13,3,1],[16,12,13],'ivory'),box([4,8,11],[8,14,13],'teal'),box([9,8,11],[12,14,13],'teal')],
'fridge':[box([1,0,1],[15,16,15],'ivory'),box([2,1,0],[14,10,2],'iron'),box([2,11,0],[14,15,2],'iron'),box([11,3,-1],[12,7,0],'teal'),box([11,12,-1],[12,14,0],'teal')],
'wardrobe':[box([0,0,1],[16,16,16]),box([1,2,0],[7,16,2],'ivory'),box([9,2,0],[15,16,2],'ivory'),box([6,11,-1],[7,15,0],'teal'),box([9,11,-1],[10,15,0],'teal')],
'lamp':[box([3,0,3],[13,2,13]),box([7,2,7],[9,10,9],'ivory'),box([3,10,3],[13,15,13],'glow'),box([2,15,2],[14,16,14],'teal')],
'rug':[box([0,0,0],[16,1,16],'teal'),box([2,1,2],[14,1.1,14],'ivory'),box([6,1.1,6],[10,1.2,10],'teal')],
'shelf':[box([0,0,13],[16,16,16]),box([0,3,0],[16,5,16],'ivory'),box([0,13,0],[16,15,16],'ivory'),box([0,0,0],[2,16,16]),box([14,0,0],[16,16,16])]
}
textures={'oak':'minecraft:block/oak_planks','ivory':'minecraft:block/quartz_block_side','teal':'minecraft:block/cyan_concrete','iron':'minecraft:block/iron_block','glow':'minecraft:block/glowstone','particle':'minecraft:block/oak_planks'}
for name,elements in models.items():
 def model(parts):return {'parent':'minecraft:block/block','textures':textures,'elements':parts,'display':{'gui':{'rotation':[30,225,0],'translation':[0,0,0],'scale':[.6,.6,.6]}}}
 write(A,f'models/block/{name}.json',model(elements))
 variants={f'facing={face}':{'model':f'lightborn:block/{name}','y':angle} for face,angle in [('north',0),('east',90),('south',180),('west',270)]}
 if name=='wardrobe':
  upper=[box([0,0,1],[16,16,16]),box([1,0,0],[7,14,2],'ivory'),box([9,0,0],[15,14,2],'ivory'),box([6,1,-1],[7,4,0],'teal'),box([9,1,-1],[10,4,0],'teal'),box([0,14,0],[16,16,16],'teal')]
  write(A,'models/block/wardrobe_upper.json',model(upper));variants={f'facing={face},half={half}':{'model':f'lightborn:block/wardrobe'+('_upper' if half=='upper' else ''),'y':angle} for face,angle in [('north',0),('east',90),('south',180),('west',270)] for half in ['lower','upper']}
  complete=elements+[{'from':[e['from'][0],e['from'][1]+16,e['from'][2]],'to':[e['to'][0],e['to'][1]+16,e['to'][2]],'faces':e['faces']} for e in upper];m=model(complete);m['display']['gui']={'rotation':[20,225,0],'translation':[0,-3,0],'scale':[.42,.42,.42]};write(A,'models/item/wardrobe.json',m)
 else:write(A,f'models/item/{name}.json',{'parent':f'lightborn:block/{name}'})
 write(A,f'blockstates/{name}.json',{'variants':variants});write(A,f'items/{name}.json',{'model':{'type':'minecraft:model','model':f'lightborn:item/{name}'}})
 write(D,f'loot_table/blocks/{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':f'lightborn:{name}'}],'condition':{'type':'minecraft:survives_explosion'}}]})
recipes={
'chair':(['P P','PWP','S S'],{'P':'#minecraft:planks','W':'minecraft:white_wool','S':'minecraft:stick'}),
'table':(['PPP','S S','S S'],{'P':'#minecraft:planks','S':'minecraft:stick'}),
'couch':(['WWW','WPW','P P'],{'P':'#minecraft:planks','W':'#minecraft:wool'}),
'fridge':(['III','GCI','III'],{'I':'minecraft:iron_ingot','C':'minecraft:ice','G':'minecraft:glass'}),
'wardrobe':(['PPP','PIP','PPP'],{'P':'#minecraft:planks','I':'minecraft:iron_ingot'}),
'lamp':([' G ','ILI',' P '],{'G':'minecraft:glass','I':'minecraft:iron_ingot','L':'minecraft:glowstone_dust','P':'#minecraft:planks'}),
'rug':(['S S','WWW'],{'S':'minecraft:string','W':'#minecraft:wool'}),
'shelf':(['P P','SSS'],{'P':'#minecraft:planks','S':'#minecraft:wooden_slabs'})}
for name,(pattern,key) in recipes.items():write(D,f'recipe/{name}.json',{'type':'minecraft:crafting_shaped','category':'misc','pattern':pattern,'key':key,'result':{'id':f'lightborn:{name}','count':1}})
f=A/'lang/en_us.json';j=json.loads(f.read_text());j.update({f'block.lightborn.{name}':name.capitalize() for name in models});f.write_text(json.dumps(j,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Drew eight furnishing models and wrote their recipes, items, block states and drops.')
