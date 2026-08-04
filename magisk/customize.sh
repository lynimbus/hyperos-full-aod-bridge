SKIPUNZIP=0

STOCK_SHA256="@STOCK_SHA256@"

ui_print "- 设备: $(getprop ro.product.device)"
ui_print "- 版本: $(getprop ro.build.version.incremental)"

CUR="$(sha256sum /system/framework/services.jar 2>/dev/null | cut -d' ' -f1)"
if [ -z "$CUR" ]; then
  abort "! 读不到 /system/framework/services.jar"
fi
if [ "$CUR" != "$STOCK_SHA256" ]; then
  ui_print "! 当前 services.jar 与补丁基线不一致"
  ui_print "!   设备: $CUR"
  ui_print "!   基线: $STOCK_SHA256"
  abort "! 请使用本机 services.jar 重新构建"
fi

set_perm_recursive "$MODPATH/system/framework" 0 0 0755 0644
ui_print "- 已就位，重启后生效"
