import sys; sys.path.insert(0,'.')
from paint import *

def alpen_morgen():
    img=sky([(0,'#1d2a4f'),(.35,'#4b4a7d'),(.58,'#d98f8a'),(.7,'#f6c39a')])
    img=glow(img,1250,700,500,'#ffd7a8',.55)
    img=clouds(img,11,.08,.4,'#f2b6b0',.35,600)
    m=np.zeros((H,W))
    img,_=layer(img,m,ridge(1,.52,.30,700,True,.01),'#f7d9d6','#a99ab8','#d9a8a8',.25)
    img=mist(img,.6,.05,'#e7b7b0',.5)
    img,_=layer(img,m,ridge(2,.62,.16,500,True),'#7c6f9a','#5d5683','#b98ea2',.25)
    img=mist(img,.66,.04,'#c99aa5',.45)
    img,m=layer(img,m,ridge(3,.68,.14,420,False),'#3b3557','#272338','#6d5c7d',.1,5,True)
    img,m=layer(img,m,ridge(4,.8,.08,300,False),'#191626','#100e18','#2a2438',0,6,True,trees=(41,(30,70)))
    return img,m

def bergsee():
    img=sky([(0,'#2f6db5'),(.45,'#79aee0'),(.6,'#cfe3f2')])
    img=clouds(img,21,.05,.35,'#ffffff',.55,520)
    m=np.zeros((H,W))
    img,_=layer(img,m,ridge(5,.52,.28,520,True,.015),'#f4f7fb','#6f86a3','#a9c2db',.2,7)
    img,m=layer(img,m,ridge(6,.6,.16,380,True),'#3f5a77','#2a3f57','#7d9bb8',.15,8,True)
    img,m=layer(img,m,ridge(7,.66,.06,260,False),'#1f3326','#14231a','#3b5a4a',.05,9,True,trees=(42,(25,55)))
    img,m=lake(img,m,.7,'#1d3550',.35)
    return img,m

def sonnenuntergang():
    img=sky([(0,'#2b1e4a'),(.3,'#8a3d6b'),(.5,'#e8734a'),(.62,'#ffc66b')])
    img=glow(img,760,610,420,'#ffe2a0',.7)
    img=disc(img,760,610,60,'#fff1c9',2)
    img=clouds(img,31,.12,.45,'#f08a6a',.35,700)
    m=np.zeros((H,W))
    img,_=layer(img,m,ridge(8,.6,.1,700,False),'#c0607a','#a24c6a','#e78a6e',.35)
    img,_=layer(img,m,ridge(9,.66,.1,600,False),'#7f3a62','#62304f','#c46a6a',.25)
    img,m=layer(img,m,ridge(10,.7,.12,520,False),'#42243f','#2d1a2c','#7a3f55',.1,11,True)
    img,m=layer(img,m,ridge(12,.84,.1,380,False),'#1b1020','#120a15','#2b1a2c',0,13,True,trees=(43,(20,45)))
    return img,m

def sternennacht():
    img=sky([(0,'#050a1a'),(.5,'#13244a'),(.66,'#2c4373')])
    img=stars(img,51,900)
    img=glow(img,1400,260,260,'#9fb6e8',.35)
    img=disc(img,1400,260,34,'#f4f1e2',1.5)
    m=np.zeros((H,W))
    img,_=layer(img,m,ridge(14,.55,.25,520,True,.01),'#3a4f78','#26375a','#3f5785',.3)
    img,m=layer(img,m,ridge(15,.62,.15,400,True),'#1b2744','#121b30','#24365a',.15,16,True)
    img,m=layer(img,m,ridge(17,.68,.05,300,False),'#0b1220','#080d17','#121b2e',0,18,True,trees=(44,(22,50)))
    img,m=lake(img,m,.71,'#0a1428',.3)
    return img,m

def fjord():
    img=sky([(0,'#20314f'),(.4,'#4f6f8f'),(.6,'#e6b3a5'),(.68,'#f4d2b8')])
    img=glow(img,960,700,450,'#ffd9c0',.4)
    img=clouds(img,61,.1,.42,'#d8a8b0',.3,650)
    m=np.zeros((H,W))
    x=np.linspace(0,1,W)
    valley=np.clip(np.abs(x-.5)*2.4,0,1)**1.6
    t=ridge(19,.66,.06,500,False)-valley*.38
    img,_=layer(img,m,ridge(20,.6,.08,400,True),'#8d8fae','#6c7193','#b7a9bb',.35)
    img,m=layer(img,m,t,'#2f3d55','#1e293b','#5a6a85',.15,21,True,trees=(45,(18,40)))
    img,m=lake(img,m,.72,'#22324a',.35)
    return img,m

def nebelwald():
    img=sky([(0,'#9aa7a4'),(.5,'#cfd6d0'),(.7,'#e4e8e2')])
    m=np.zeros((H,W))
    img,_=layer(img,m,ridge(22,.5,.12,700,False),'#a3b0aa','#9aa8a1','#cfd6d0',.4,None,False,trees=(46,(14,26)))
    img=mist(img,.56,.05,'#e4e8e2',.6)
    img,_=layer(img,m,ridge(23,.58,.1,600,False),'#7b8c84','#6d7f76','#c3ccc6',.3,None,False,trees=(47,(18,34)))
    img=mist(img,.63,.05,'#dfe4de',.55)
    img,m=layer(img,m,ridge(24,.64,.1,500,False),'#4a5d53','#3d5047','#9aa8a1',.15,25,True,trees=(48,(24,46)))
    img=mist(img,.72,.04,'#d5dbd5',.4)
    img,m=layer(img,m,ridge(26,.8,.08,400,False),'#1f2d26','#17231d','#3d4d45',0,27,True,trees=(49,(40,90)))
    return img,m

def winter():
    img=sky([(0,'#5b8fc9'),(.45,'#a9c8e8'),(.62,'#e3eef7')])
    img=clouds(img,71,.06,.3,'#ffffff',.3,600)
    m=np.zeros((H,W))
    img,_=layer(img,m,ridge(28,.5,.28,560,True,.012),'#ffffff','#c3d3e6','#dbe7f3',.15)
    img,_=layer(img,m,ridge(29,.58,.16,450,True,.008),'#e9f0f7','#9db2cc','#c7d6e7',.15)
    img=mist(img,.63,.04,'#eef4fa',.5)
    img,m=layer(img,m,ridge(30,.66,.12,420,True),'#7f98b8','#5d7697','#a9bdd4',.1,31,True)
    img,m=layer(img,m,ridge(32,.78,.06,300,False),'#2e4258','#1f2f40','#5d7697',0,33,True,trees=(50,(30,64)))
    return img,m

def duenen():
    img=sky([(0,'#3a2f6b'),(.35,'#c0607a'),(.55,'#f2a65a'),(.66,'#fbd38d')])
    img=glow(img,1300,640,380,'#ffe0a0',.6)
    img=disc(img,1300,640,52,'#fff3cf',2)
    m=np.zeros((H,W))
    img,_=layer(img,m,ridge(34,.63,.06,900,False),'#d98a5a','#b86a45','#f0a870',.35)
    img,m=layer(img,m,ridge(35,.62,.09,800,False),'#b0603c','#8a462c','#d98a5a',.15,36,True)
    img,m=layer(img,m,ridge(37,.8,.1,700,False),'#6e3523','#4e2418','#8a462c',.05,38,True)
    return img,m

for f in [alpen_morgen,bergsee,sonnenuntergang,sternennacht,fjord,nebelwald,winter,duenen]:
    img,m=f(); save(img,m,f.__name__); print(f.__name__)
