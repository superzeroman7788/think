import math, os

def star_path(cx, cy, R, r, rot_deg=-90, n=5):
    pts=[]
    for k in range(n):
        ao = math.radians(rot_deg + k*360/n)
        ai = math.radians(rot_deg + (k+0.5)*360/n)
        pts.append((cx+R*math.cos(ao), cy+R*math.sin(ao)))
        pts.append((cx+r*math.cos(ai), cy+r*math.sin(ai)))
    d="M %.2f %.2f " % pts[0] + " ".join("L %.2f %.2f"%(x,y) for x,y in pts[1:]) + " Z"
    return d

def ring_path(cx, cy, rad):
    # two-arc full circle
    return ("M %.2f %.2f a %.2f %.2f 0 1 0 %.2f 0 a %.2f %.2f 0 1 0 %.2f 0 Z"
            % (cx-rad, cy, rad, rad, 2*rad, rad, rad, -2*rad))

# ---------- iOS 1024 master SVG ----------
W=1024; c=512
star1024 = star_path(c, c, 196, 80)
ring1024 = ring_path(c, c, 286)
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{W}" viewBox="0 0 {W} {W}">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#B5654A"/>
      <stop offset="1" stop-color="#9E5238"/>
    </linearGradient>
  </defs>
  <rect width="{W}" height="{W}" fill="url(#bg)"/>
  <path d="{ring1024}" fill="none" stroke="#FBF5F0" stroke-width="22" opacity="0.78"/>
  <path d="{star1024}" fill="#FBF5F0"/>
</svg>'''
open("/tmp/icon_master.svg","w").write(svg)

# ---------- Android vectors (108 viewport) ----------
star108 = star_path(54, 54, 20.5, 8.4)
ring108 = ring_path(54, 54, 30.0)

fg = f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="{ring108}" android:strokeColor="#FBF5F0" android:strokeWidth="2.3" android:fillColor="#00000000" android:strokeAlpha="0.78"/>
    <path android:pathData="{star108}" android:fillColor="#FBF5F0"/>
</vector>'''

bg = '''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="0" android:endY="108">
                <item android:offset="0" android:color="#FFB5654A"/>
                <item android:offset="1" android:color="#FF9E5238"/>
            </gradient>
        </aapt:attr>
    </path>
</vector>'''

base="app/src/main/res"
os.makedirs(f"{base}/drawable", exist_ok=True)
os.makedirs(f"{base}/mipmap-anydpi-v26", exist_ok=True)
open(f"{base}/drawable/ic_launcher_foreground.xml","w").write(fg)
open(f"{base}/drawable/ic_launcher_background.xml","w").write(bg)

adaptive = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>'''
open(f"{base}/mipmap-anydpi-v26/ic_launcher.xml","w").write(adaptive)
open(f"{base}/mipmap-anydpi-v26/ic_launcher_round.xml","w").write(adaptive)
print("android vectors + master svg written")
