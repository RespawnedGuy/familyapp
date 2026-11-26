# WCAG contrast of the theme colours in app/shared/.../ui/Theme.kt (values copied by hand; update both together).
# Run: python3 contrast.py
def lum(h):
    h=h[-6:];r,g,b=[int(h[i:i+2],16)/255 for i in (0,2,4)]
    f=lambda c: c/12.92 if c<=0.04045 else ((c+0.055)/1.055)**2.4
    return 0.2126*f(r)+0.7152*f(g)+0.0722*f(b)
def cr(a,b):
    la,lb=sorted([lum(a),lum(b)],reverse=True);return (la+0.05)/(lb+0.05)
L=dict(bg="F6F5F2",card="FFFFFF",text="1D1C1A",t2="5C5A55",t3="6E6B65",acc="0E6B66",onacc="FFFFFF",over="B3261E",soon="8A5300",outline="D9D6CF",p0="2F5DA8",p1="A84B0C",p2="7B3FA0",p3="2C7A4B")
D=dict(bg="151513",card="201F1C",text="ECEAE4",t2="B0ADA6",t3="B0ADA6",acc="5FC4BC",onacc="0B2E2C",over="FF8A80",soon="F2B85B",outline="3A3934",p0="8FB0EC",p1="F0A06A",p2="C9A0E6",p3="7FCB9B")
for n,P in (("hell",L),("dunkel",D)):
    for fg in ["text","t2","t3","acc","over","soon","p0","p1","p2","p3","outline"]:
        print(n,fg,"auf card %.2f"%cr(P[fg],P["card"]),"auf bg %.2f"%cr(P[fg],P["bg"]))
    print(n,"onacc/acc %.2f"%cr(P["onacc"],P["acc"]))
    print(n,"snackbar bg-on-text %.2f"%cr(P["bg"],P["text"]))
    print(n,"outline vs card (Swipe-Hintergrund/Rahmen) %.2f"%cr(P["outline"],P["card"]))
    print(n,"t2 auf outline (Swipe vor Schwelle text auf outline) %.2f"%cr(P["text"],P["outline"]))
    print(n,"bg auf t2 (Swipe links Text) %.2f"%cr(P["bg"],P["t2"]))
    for p in ["p0","p1","p2","p3"]:
        # initials on person dot: which text color? check later
        print(n,p,"weiss %.2f / text %.2f / card %.2f"%(cr("FFFFFF",P[p]),cr(P["text"],P[p]),cr(P["card"],P[p])))
