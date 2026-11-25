import ctypes, sys, time
x=ctypes.CDLL("libX11.so.6"); xt=ctypes.CDLL("libXtst.so.6")
x.XOpenDisplay.restype=ctypes.c_void_p
d=ctypes.c_void_p(x.XOpenDisplay(b":99"))
x.XKeysymToKeycode.restype=ctypes.c_ubyte
x.XStringToKeysym.restype=ctypes.c_ulong
def flush(): x.XFlush(d)
def move(px,py): xt.XTestFakeMotionEvent(d,0,px,py,0); flush()
def rel(dx,dy): xt.XTestFakeRelativeMotionEvent(d,dx,dy,0); flush()
def click(px,py,b=1):
    move(px,py); time.sleep(0.15); xt.XTestFakeButtonEvent(d,b,1,0); flush(); time.sleep(0.08); xt.XTestFakeButtonEvent(d,b,0,0); flush()
def key(name, hold=0.08):
    kc=x.XKeysymToKeycode(d, x.XStringToKeysym(name.encode()))
    xt.XTestFakeKeyEvent(d,kc,1,0); flush(); time.sleep(hold); xt.XTestFakeKeyEvent(d,kc,0,0); flush()
def keydown(name,down):
    kc=x.XKeysymToKeycode(d, x.XStringToKeysym(name.encode())); xt.XTestFakeKeyEvent(d,kc,1 if down else 0,0); flush()
cmds=sys.argv[1:]
for c in cmds:
    p=c.split(":")
    if p[0]=="click": click(int(p[1]),int(p[2]))
    elif p[0]=="move": move(int(p[1]),int(p[2]))
    elif p[0]=="rel":
        n=int(p[3]) if len(p)>3 else 1
        for i in range(n): rel(int(p[1]),int(p[2])); time.sleep(0.016)
    elif p[0]=="key": key(p[1], float(p[2]) if len(p)>2 else 0.08)
    elif p[0]=="down": keydown(p[1],True)
    elif p[0]=="up": keydown(p[1],False)
    elif p[0]=="sleep": time.sleep(float(p[1]))
    elif p[0]=="type":
        # Characters that need shift on a US layout; ' ' is typed as space.
        shifted={'~':'grave','{':'bracketleft','}':'bracketright','@':'2','^':'6','!':'1','"':'apostrophe','_':'minus','+':'equal','<':'comma','>':'period','?':'slash',':':'semicolon','|':'backslash','(':'9',')':'0','*':'8','#':'3','$':'4','%':'5','&':'7'}
        names={' ':'space','/':'slash','.':'period',',':'comma','-':'minus','=':'equal','[':'bracketleft',']':'bracketright',';':'semicolon',"'":'apostrophe','`':'grave','\\':'backslash'}
        text=c[len("type:"):]
        for ch in text:
            if ch in shifted or ch.isupper():
                keydown("Shift_L",True); key(shifted.get(ch, ch.lower())); keydown("Shift_L",False)
            else:
                key(names.get(ch, ch))
