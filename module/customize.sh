#!/system/bin/sh
# ============================================================
#  WOL Relay · 模块安装脚本
#  作者：神仙道  QQ 1974147300
# ============================================================
#
#  这里有两个 Magisk 的常见陷阱，踩过才知道：
#
#  【陷阱 1】不要写 MODPATH=${0%/*}
#      Magisk 是用 `. "$MODPATH/customize.sh"` 的方式 source 本脚本的，
#      此时 $0 指向的是 update-binary，不是本文件。
#      写 MODPATH=${0%/*} 会把 Magisk 已经设好的正确路径覆盖掉，
#      后续 unzip -d "$MODPATH" 就解到别处去了，
#      结果是模块目录空壳，Magisk 里显示 "wolrelay ，作者 "（都读不到）。
#
#  【陷阱 2】不要设 SKIPUNZIP=1
#      交给 Magisk 自己解压，它知道该往哪解。设了就得自己处理，
#      路径一错全错。

ui_print "=================================="
ui_print "  WOL Relay 中继守护"
ui_print "  作者：神仙道  QQ 1974147300"
ui_print "=================================="
ui_print "- 安装位置：$MODPATH"

MISSING=0
for f in module.prop service.sh wolrelay; do
    if [ -f "$MODPATH/$f" ]; then
        ui_print "- 检查通过：$f"
    else
        ui_print "- 缺少文件：$f"
        MISSING=1
    fi
done

if [ "$MISSING" = "1" ]; then
    ui_print "- 安装包不完整，请重新下载"
    abort "- 缺少关键文件，已中止"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/customize.sh" 0 0 0755
set_perm "$MODPATH/doctor.sh" 0 0 0755
set_perm "$MODPATH/wolrelay" 0 0 0755

# 配置放 /data/adb/wol-relay/，不放模块目录。
# Magisk 升级模块会整个换掉 $MODPATH，放里面的 config.json 会跟着丢，
# 用户改好的 MAC 就白填了。模块目录里的 example 只是种子。
DATA=/data/adb/wol-relay
mkdir -p "$DATA"
if [ -f "$MODPATH/config.example.json" ] && [ ! -f "$DATA/config.json" ]; then
    cp -f "$MODPATH/config.example.json" "$DATA/config.json"
    set_perm "$DATA/config.json" 0 0 0600
    ui_print "- 已生成 $DATA/config.json，记得改成你自己的 MAC"
fi

ui_print ""
ui_print "下一步："
ui_print "  1. 编辑 $DATA/config.json，把 mac 改成你要唤醒的网卡"
ui_print "  2. 重启手机"
ui_print "  3. 日志在 /data/local/tmp/wolrelay.log"
ui_print ""
ui_print "改完 MAC 重启后，跑一遍自检确认五层都通："
ui_print "  su -c 'sh /data/adb/modules/wol-relay/doctor.sh'"
ui_print ""
ui_print "- 重启后生效"
