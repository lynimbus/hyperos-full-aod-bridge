SKIPUNZIP=0

ui_print "- 设备: $(getprop ro.product.device)"
ui_print "- 版本: $(getprop ro.build.version.incremental)"

set_perm_recursive "$MODPATH/system/framework" 0 0 0755 0644
ui_print "- 已就位，重启后生效"
