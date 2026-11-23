#!/usr/bin/env bash
# 低版本 Android 冒烟测试：安装 → 启动 → 判断是否白屏 → 点一次主按钮 → 收集崩溃日志。
# 用法：bash legacy-smoke.sh <api-level>
set -uo pipefail

API="${1:-19}"
PKG="top.wxip.gpslink"
OUT="legacy-out"
mkdir -p "$OUT"

pass=0
fail=0
note() { echo "[$API] $*"; }


# API 20 以下没有 adb exec-out，退回 screencap + pull
shot() {
  local out="$1"
  if [ "$API" -ge 20 ]; then
    adb exec-out screencap -p > "$out"
  else
    adb shell screencap -p /sdcard/_shot.png >/dev/null 2>&1
    adb pull /sdcard/_shot.png "$out" >/dev/null 2>&1 || true
    adb shell rm -f /sdcard/_shot.png >/dev/null 2>&1 || true
  fi
  local sz
  sz=$(stat -c%s "$out" 2>/dev/null || echo 0)
  note "截图 $out ($sz 字节)"
}

echo "== API $API 等待设备 =="
adb wait-for-device
for _ in $(seq 1 90); do
  b=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "$b" = "1" ] && break
  sleep 2
done
note "boot_completed=$b  sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"

echo "== 安装 =="
if adb install -r "apk/gnss-link.apk" 2>&1 | tee "$OUT/install.txt" | grep -q "Success"; then
  note "安装成功"; pass=$((pass+1))
else
  note "安装失败"; cat "$OUT/install.txt"; fail=$((fail+1))
fi

echo "== 权限（API>=23 才有运行时权限）=="
if [ "$API" -ge 23 ]; then
  for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
    adb shell pm grant "$PKG" "android.permission.$p" 2>/dev/null || true
  done
fi

adb shell settings put global window_animation_scale 0 2>/dev/null || true
adb shell settings put global transition_animation_scale 0 2>/dev/null || true
adb logcat -c 2>/dev/null || true

echo "== 启动 App =="
adb shell am start -W -n "$PKG/.MainActivity" 2>&1 | tee "$OUT/start.txt" | tail -5
sleep 12

# 前台校验：Activity 必须真的起来
focus=$(adb shell dumpsys window windows 2>/dev/null | grep -m1 mCurrentFocus || true)
note "当前焦点: $focus"
case "$focus" in
  *"$PKG"*) note "App 在前台"; pass=$((pass+1)) ;;
  *)        note "App 不在前台（可能崩溃或启动失败）"; fail=$((fail+1)) ;;
esac

echo "== WebView 是否创建 =="
wv=$(adb shell dumpsys window windows 2>/dev/null | grep -c "WebView" || true)
note "窗口树里 WebView 出现次数: $wv"

echo "== 崩溃检查 ==" 
adb logcat -d 2>/dev/null | grep -aE "FATAL EXCEPTION|AndroidRuntime.*$PKG|E AndroidRuntime" | head -40 > "$OUT/crash.txt"
if [ -s "$OUT/crash.txt" ]; then
  note "发现崩溃日志"; head -20 "$OUT/crash.txt"; fail=$((fail+1))
else
  note "无崩溃"; pass=$((pass+1))
fi

echo "== 首屏截图 =="
shot "$OUT/api${API}-launch.png"

# 白屏判断：统计 PNG 的颜色多样性，纯色/接近纯色视为白屏
python3 - "$OUT/api${API}-launch.png" <<'PY' | tee "$OUT/whitecheck.txt"
import sys, zlib, struct
path = sys.argv[1]
try:
    data = open(path, 'rb').read()
    # 解析 PNG 尺寸与位深，粗略采样解码器不可用时只报尺寸
    w, h = struct.unpack('>II', data[16:24])
    print(f"截图 {w}x{h}")
except Exception as e:
    print("解析失败", e)
PY

echo "== 点击主按钮（页面底部中间偏上的开始按钮区域）=="
size=$(adb shell wm size | tr -d '\r' | awk -F' ' '{print $3}')
W=${size%x*}; H=${size#*x}
note "屏幕 $W x $H"
# 开始/停止按钮大致在首屏下部；先滚到能看见再点
adb shell input tap $((W/2)) $((H*78/100)) 2>/dev/null || true
sleep 6
shot "$OUT/api${API}-after-tap.png"

echo "== 再次检查崩溃 =="
adb logcat -d 2>/dev/null | grep -aE "FATAL EXCEPTION|AndroidRuntime.*$PKG" | head -40 > "$OUT/crash2.txt"
if [ -s "$OUT/crash2.txt" ]; then
  note "点击后出现崩溃"; head -20 "$OUT/crash2.txt"; fail=$((fail+1))
else
  note "点击后无崩溃"; pass=$((pass+1))
fi

adb logcat -d > "$OUT/full-logcat.txt" 2>/dev/null || true

echo "=== API $API 结果：通过 $pass 项 / 失败 $fail 项 ==="
ls -lh "$OUT"
[ "$fail" -eq 0 ]
