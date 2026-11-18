//go:build !windows

package main

import "syscall"

// setBroadcast 给 UDP socket 打开 SO_BROADCAST。
//
// 为什么必须显式打开：
// Linux（以及 Android）内核默认不允许往广播地址发包，
// 没开这个选项时 sendto 会直接返回 EACCES "permission denied"，
// 表现就是日志里每个目标都是"发失败"，但程序本身不报错、也不崩。
// Go 的 net.Dial 不会替你开这个选项，必须自己设。
//
// Windows 的行为宽松一些，但这行设置在那上面也不会出错，
// 所以两个平台都走这段逻辑（见 sockopt_windows.go）。
func setBroadcast(fd uintptr) {
	// 失败就算了：有些平台/权限下会返回 ENOPROTOOPT，
	// 这时 socket 本身可能已经是允许广播的，不该因此中断唤醒流程。
	_ = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_BROADCAST, 1)
}
