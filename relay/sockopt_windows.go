//go:build windows

package main

import "syscall"

// setBroadcast 见 sockopt_unix.go 的说明。
// Windows 的 SetsockoptInt 第一个参数是 Handle 而不是 int，所以单独放一个文件，
// 用 build tag 区分，免得为了一个类型转换把主逻辑搞乱。
func setBroadcast(fd uintptr) {
	_ = syscall.SetsockoptInt(syscall.Handle(fd), syscall.SOL_SOCKET, syscall.SO_BROADCAST, 1)
}
