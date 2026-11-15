import numpy as np
from PIL import Image, ImageFilter
W,H=1920,1080
rng=np.random.default_rng

def lerp(a,b,t): return a+(b-a)*t
def col(h): h=h.lstrip('#'); return np.array([int(h[i:i+2],16) for i in (0,2,4)],float)

def noise1d(n, seed, scale):
    r=rng(seed); pts=r.random(int(n/scale)+3)
    x=np.arange(n)/scale; i=x.astype(int); f=x-i; f=f*f*(3-2*f)
    return pts[i]*(1-f)+pts[i+1]*f

def fbm1d(n, seed, scale, oct=6, ridged=False):
    v=np.zeros(n); amp=1; tot=0
    for o in range(oct):
        nz=noise1d(n, seed+o*17, scale/(2**o))
        if ridged: nz=1-np.abs(nz*2-1)
        v+=nz*amp; tot+=amp; amp*=0.5
    return v/tot

def noise2d(w,h,seed,scale,oct=5):
    r=rng(seed); out=np.zeros((h,w))
    amp=1; tot=0
    for o in range(oct):
        s=scale/(2**o); gw=int(w/s)+3; gh=int(h/s)+3
        g=r.random((gh,gw))
        im=Image.fromarray((g*255).astype(np.uint8)).resize((int(gw*s),int(gh*s)),Image.BICUBIC)
        a=np.asarray(im,float)[:h,:w]/255
        out+=a*amp; tot+=amp; amp*=0.5
    return out/tot

def sky(stops):
    y=np.linspace(0,1,H)[:,None]
    img=np.zeros((H,W,3))
    ps=[p for p,_ in stops]; cs=[col(c) for _,c in stops]
    for k in range(3):
        img[:,:,k]=np.interp(y[:,0],ps,[c[k] for c in cs])[:,None]
    return img

def glow(img, cx, cy, r, c, strength):
    yy,xx=np.mgrid[0:H,0:W]; d=np.sqrt((xx-cx)**2+(yy-cy)**2)
    a=np.exp(-(d/r)**2)*strength
    return img*(1-a[...,None])+col(c)*a[...,None]

def disc(img, cx, cy, r, c, soft=2.0):
    yy,xx=np.mgrid[0:H,0:W]; d=np.sqrt((xx-cx)**2+(yy-cy)**2)
    a=np.clip((r-d)/soft,0,1)
    return img*(1-a[...,None])+col(c)*a[...,None]

def stars(img, seed, n, top=0.65):
    r=rng(seed)
    for _ in range(n):
        x=r.integers(0,W); y=int(r.random()**1.6*H*top); b=r.random()**3
        rad=1 if b<0.7 else 2
        img[max(0,y-rad+1):y+rad, max(0,x-rad+1):x+rad]=np.maximum(img[max(0,y-rad+1):y+rad, max(0,x-rad+1):x+rad], 120+135*b)
    return img

def clouds(img, seed, y0, y1, c, alpha, scale=500):
    n=noise2d(W,H,seed,scale)
    yy=np.linspace(0,1,H)[:,None]
    band=np.clip((yy-y0)/0.06,0,1)*np.clip((y1-yy)/0.06,0,1)
    a=np.clip((n-0.5)*3,0,1)*band*alpha
    return img*(1-a[...,None])+col(c)*a[...,None]

def ridge(seed, base, amp, scale, ridged=True, jag=0.0):
    v=fbm1d(W, seed, scale, 7, ridged)
    v=(v-v.min())/(v.max()-v.min())
    if jag: v=v+ (fbm1d(W,seed+99,18,3)-.5)*jag
    return base-amp*v   # top y in fraction

def layer(img, mask_acc, top, c_top, c_bot, haze_col, haze, texture_seed=None, front=False, trees=None):
    tops=(top*H).astype(int)
    yy=np.arange(H)[:,None]
    m=(yy>=tops[None,:]).astype(float)
    # soft edge
    m=np.asarray(Image.fromarray((m*255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8)),float)/255
    if trees:
        r=rng(trees[0]); tm=np.zeros((H,W))
        x=0
        while x<W:
            h=int(r.uniform(*trees[1])); w=int(h*r.uniform(.28,.38)); y=tops[min(x,W-1)]+int(h*0.15)
            for k in range(h):
                half=int(w/2*(k/h))
                yk=y-h+k
                if 0<=yk<H: tm[yk, max(0,x-half):min(W,x+half+1)]=1
            x+=int(r.uniform(.45,.9)*w)+1
        tm=np.asarray(Image.fromarray((tm*255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.7)),float)/255
        m=np.maximum(m,tm)
    t=np.clip((yy-tops.min())/(H-tops.min()+1),0,1)
    c=col(c_top)*(1-t[...,None])+col(c_bot)*t[...,None]
    c=c*(1-haze)+col(haze_col)*haze
    if texture_seed is not None:
        n=noise2d(W,H,texture_seed,90,4)
        c=c*(0.9+0.2*n[...,None])
    img=img*(1-m[...,None])+c*m[...,None]
    if front: mask_acc=np.maximum(mask_acc,m)
    return img,mask_acc

def mist(img, y, height, c, alpha):
    yy=np.linspace(0,1,H)[:,None]
    a=np.exp(-((yy-y)/height)**2)*alpha
    n=noise2d(W,H,7,400,3)
    a=a*(0.6+0.8*n)
    return img*(1-a[...,None])+col(c)*a[...,None]

def lake(img, mask_acc, y, tint, strength, ripple_seed=3):
    yl=int(y*H)
    above=img[:yl][::-1]
    n=yl if yl<H-yl else H-yl
    refl=above[:H-yl] if above.shape[0]>=H-yl else np.vstack([above, np.repeat(above[-1:],H-yl-above.shape[0],0)])
    r=rng(ripple_seed)
    shift=(np.sin(np.arange(H-yl)[:,None]*0.9+r.random()*6)*2).astype(int)
    out=refl.copy()
    for i in range(H-yl):
        out[i]=np.roll(refl[i], int(shift[i,0]), axis=0)
    out=out*(1-strength)+col(tint)*strength
    img[yl:]=out
    mask_acc[yl:]=1
    return img,mask_acc

def save(img, mask, name):
    Image.fromarray(np.clip(img,0,255).astype(np.uint8)).save(f'{name}.jpg',quality=90,optimize=True)
    Image.fromarray((np.clip(mask,0,1)*255).astype(np.uint8)).resize((960,540),Image.LANCZOS).save(f'{name}_mask.png',optimize=True)
