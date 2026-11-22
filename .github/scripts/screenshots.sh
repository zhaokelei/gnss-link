#!/usr/bin/env bash
# 在 CI 的 Android 模拟器里安装 APK，按多种分辨率/横竖屏截取真实运行界面。
# 由 .github/workflows/build-apk.yml 的 screenshot 任务调用。
set -euo pipefail

APK="${APK_PATH:-gnss-link-universal.apk}"
OUT="${OUT_DIR:-screenshots}"
PKG="top.wxip.gpslink"

mkdir -p "$OUT"

echo "== 等待设备就绪 =="
adb wait-for-device
for i in $(seq 1 60); do
  booted=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "$booted" = "1" ] && break
  sleep 2
done
echo "boot_completed=$booted"

echo "== 安装 APK =="
adb install -r -t "$APK"

echo "== 预授权，避免权限弹窗遮挡截图 =="
for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do
  adb shell pm grant "$PKG" "android.permission.$perm" 2>/dev/null || true
done
# 允许模拟位置，接收端才会真正注入（截图不依赖，但避免弹提示）
adb shell appops set "$PKG" android:mock_location allow 2>/dev/null || true

# 关闭动画，截图更稳定
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put system accelerometer_rotation 0

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

for cfg in "${CONFIGS[@]}"; do
  read -r size dpi tag <<< "$cfg"
  W=${size%x*}
  H=${size#*x}
  echo "== $tag  $size @ ${dpi}dpi =="

  adb shell wm size "$size"
  adb shell wm density "$dpi"
  # 横屏配置用 rotation 0 + 宽>高，系统会按 LANDSCAPE 排版
  adb shell settings put system user_rotation 0

  adb shell am force-stop "$PKG"
  sleep 1
  adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
  # WebView 首屏与桥接初始化需要时间
  sleep 8

  # 计算底栏四个页签的点击坐标：dock 宽 min(370dp, 屏宽)，高 56dp，离底 12dp
  read -r X1 Y1 X2 Y2 X3 Y3 X4 Y4 <<< "$(python3 - "$W" "$H" "$dpi" <<'PY'
import sys
W, H, dpi = map(float, sys.argv[1:4])
k = dpi / 160.0                    # 1 CSS px = k 物理像素
w_dp, h_dp = W / k, H / k
dock = min(370.0, w_dp - 24)
start = (w_dp - dock) / 2
tab = dock / 4
y = h_dp - 12 - 28                 # 底栏 padding 12dp + 半个 56dp 高度
xs = [(start + tab * (i + 0.5)) * k for i in range(4)]
print(int(xs[0]), int(y*k), int(xs[1]), int(y*k), int(xs[2]), int(y*k), int(xs[3]), int(y*k))
PY
)"

  coords=("$X1 $Y1" "$X2 $Y2" "$X3 $Y3" "$X4 $Y4")
  for i in 0 1 2 3; do
    adb shell input tap ${coords[$i]}
    sleep 2
    adb exec-out screencap -p > "$OUT/${tag}-${PAGES[$i]}.png"
    echo "  截图 $tag-${PAGES[$i]}.png"
  done
done

# 还原，避免影响后续步骤
adb shell wm size reset
adb shell wm density reset

echo "== 完成，共 $(ls -1 "$OUT" | wc -l) 张 =="
ls -lh "$OUT"
