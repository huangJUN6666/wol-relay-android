package main

// 魔术包与 MAC 相关的小工具。

import (
	"fmt"
	"hash/fnv"
	"strings"
)

// magicPacket 拼一个标准的 WOL 魔术包：6 个 0xFF + 16 次 MAC，共 102 字节。
//
// 这是全项目唯一的魔法数字来源。App 端 WolSender 里有一份同样的实现，
// 两边任何一处改动都必须同步，否则会出现"中继发了、电脑不理"的怪事。
func magicPacket(mac string) ([]byte, error) {
	n := normMAC(mac)
	if len(n) != 12 {
		return nil, fmt.Errorf("MAC 必须是 12 位十六进制，收到的是 %q", mac)
	}
	hw := make([]byte, 6)
	for i := 0; i < 6; i++ {
		var b int
		if _, err := fmt.Sscanf(n[i*2:i*2+2], "%02x", &b); err != nil {
			return nil, fmt.Errorf("MAC 第 %d 段解析失败: %v", i+1, err)
		}
		hw[i] = byte(b)
	}
	pkt := make([]byte, 102)
	for i := 0; i < 6; i++ {
		pkt[i] = 0xFF
	}
	for i := 1; i <= 16; i++ {
		copy(pkt[i*6:], hw)
	}
	return pkt, nil
}

// maskMAC 只留前三段，后面打码。
// MAC 的前 3 字节是厂商 OUI，不敏感；后 3 字节才是这台设备的唯一标识。
func maskMAC(mac string) string {
	hex := normMAC(mac)
	if len(hex) != 12 {
		return ""
	}
	var b strings.Builder
	for i := 0; i < 12; i += 2 {
		if i > 0 {
			b.WriteByte(':')
		}
		if i < 6 {
			b.WriteString(hex[i : i+2])
		} else {
			b.WriteString("XX")
		}
	}
	return b.String()
}

// macFingerprint 是 MAC 的短指纹，用来让 App 判断"中继配的是不是我那台电脑"，
// 而不用把完整 MAC 暴露出去。
// 用 FNV-1a 就够：目的不是防碰撞攻击，只是让 App 在一次扫描里做等值比较。
func macFingerprint(mac string) string {
	hex := normMAC(mac)
	if len(hex) != 12 {
		return ""
	}
	h := fnv.New32a()
	h.Write([]byte(hex))
	return fmt.Sprintf("%08x", h.Sum32())
}

// tokenEqual 做常量时间比较。
//
// 用 == 比字符串，第一个字节不对就立刻返回，理论上能用响应时间逐字节猜出来。
// 这个中继挂在局域网里，攻击者就在同一网段，往返延迟低到能测出纳秒级差异，
// 所以不能用 ==。subtle 语义自己写一行，省得拉 crypto/subtle 也就一句。
func tokenEqual(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	var diff byte
	for i := 0; i < len(a); i++ {
		diff |= a[i] ^ b[i]
	}
	return diff == 0
}
