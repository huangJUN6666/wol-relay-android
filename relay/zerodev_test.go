package main

import (
	"net/http/httptest"
	"strings"
	"testing"
)

// 零设备配置下 /status 和 /confirm 都不能 panic。
// 正常启动时 main 会拦住零设备，但别人手动起、或者改了 config 之后
// 老进程还在跑，都可能走到这里。一个越界 = 客户端只看到 500 没有原因。
func TestZeroDevices(t *testing.T) {
	c := Config{Listen: ":8080", UDPPort: 9, ConfirmTimeout: 5}
	mux := routes(c)

	for _, path := range []string{"/status", "/status?full=1", "/confirm", "/confirm?name=x"} {
		req := httptest.NewRequest("GET", path, nil)
		rec := httptest.NewRecorder()
		func() {
			defer func() {
				if p := recover(); p != nil {
					t.Fatalf("%s panic: %v", path, p)
				}
			}()
			mux.ServeHTTP(rec, req)
		}()
		if rec.Code >= 500 {
			t.Errorf("%s -> %d %s", path, rec.Code, rec.Body.String())
		}
		t.Logf("%s -> %d %s", path, rec.Code, strings.TrimSpace(rec.Body.String()))
	}
}
