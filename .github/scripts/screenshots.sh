#!/usr/bin/env bash
# 在 CI 的 Android 模拟器里安装 APK，按多种分辨率/横竖屏截取真实运行界面。
# 由 .github/workflows/build-apk.yml 的 screenshot 任务调用。
set -uo pipefail

APK="${APK_PATH:-gnss-link-universal.apk}"
OUT="${OUT_DIR:-screenshots}"
PKG="top.wxip.gpslink"

mkdir -p "$OUT"

echo "== 等待设备就绪 =="
adb wait-for-device
for _ in $(seq 1 60); do
  booted=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "$booted" = "1" ] && break
  sleep 2
done
echo "boot_completed=$booted"

echo "== 安装 APK =="
adb install -r -t "$APK" || { echo "安装失败"; exit 1; }

echo "== 预授权，避免权限弹窗遮挡截图 =="
for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do
  adb shell pm grant "$PKG" "android.permission.$perm" 2>/dev/null || true
done
adb shell appops set "$PKG" android:mock_location allow 2>/dev/null || true

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0

# 分辨率矩阵：物理宽 x 高, density dpi, 标签
CONFIGS=(
  "720x1280 320 phone-small"
  "1080x2400 420 phone-standard"
  "1440x3120 560 phone-hd"
  "1536x2048 320 tablet-portrait"
  "1920x1080 240 car-1080p"
  "2560x1440 320 car-2k"
  "3840x2160 320 car-4k"
  "2400x900 240 car-ultrawide"
)

PAGES=("link" "satellites" "settings" "about")

# 当前焦点是否在本应用；用于确认启动成功，避免点到桌面图标
app_focused() {
  local f
  f=$(adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus || true)
  case "$f" in
    *"$PKG"*) return 0 ;;
    *) return 1 ;;
  esac
}

wait_app() {
  for _ in $(seq 1 25); do
    app_focused && return 0
    sleep 1
  done
  return 1
}

launch_app() {
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  sleep 1
  adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
  if wait_app; then
    sleep 3
    return 0
  fi
  echo "  !! 启动未到前台，重试一次"
  adb logcat -d -t 120 2>/dev/null | grep -aiE "gpslink|AndroidRuntime|FATAL" | tail -15 || true
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  sleep 1
  adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
  wait_app && { sleep 3; return 0; }
  return 1
}

for cfg in "${CONFIGS[@]}"; do
  read -r size dpi tag <<< "$cfg"
  W=${size%x*}
  H=${size#*x}
  echo "== $tag  $size @ ${dpi}dpi =="

  adb shell wm size "$size" >/dev/null
  adb shell wm density "$dpi" >/dev/null
  # 改完显示参数后给系统一点时间重建 surface
  sleep 3

  if ! launch_app; then
    echo "  !! 跳过 $tag（应用未正常启动）"
    continue
  fi

  # 底栏四个页签的点击坐标：dock 宽 min(370dp, 屏宽-24)，高 56dp，离底 12dp
  read -r X1 Y1 X2 Y2 X3 Y3 X4 Y4 <<< "$(python3 - "$W" "$H" "$dpi" <<'PY'
import sys
W, H, dpi = map(float, sys.argv[1:4])
k = dpi / 160.0                     # 1 CSS px 对应的物理像素
w_dp, h_dp = W / k, H / k
dock = min(370.0, w_dp - 24)
start = (w_dp - dock) / 2
tab = dock / 4
y = (h_dp - 12 - 28) * k
xs = [int((start + tab * (i + 0.5)) * k) for i in range(4)]
print(xs[0], int(y), xs[1], int(y), xs[2], int(y), xs[3], int(y))
PY
)"

  coords=("$X1 $Y1" "$X2 $Y2" "$X3 $Y3" "$X4 $Y4")
  for i in 0 1 2 3; do
    # 每次点击前确认还在本应用，避免误触桌面图标后拍出别的东西
    if ! app_focused; then
      echo "   !! 点击前已离开应用，重新拉起"
      launch_app || break
    fi
    adb shell input tap ${coords[$i]} >/dev/null 2>&1 || true
    sleep 2
    if app_focused; then
      adb exec-out screencap -p > "$OUT/${tag}-${PAGES[$i]}.png"
      echo "   截图 ${tag}-${PAGES[$i]}.png"
    else
      echo "   !! ${PAGES[$i]} 截图时不在应用，跳过"
    fi
  done
done

adb shell wm size reset >/dev/null 2>&1 || true
adb shell wm density reset >/dev/null 2>&1 || true

echo "== 完成，共 $(ls -1 "$OUT" 2>/dev/null | wc -l) 张 =="
ls -lh "$OUT" | head -40
