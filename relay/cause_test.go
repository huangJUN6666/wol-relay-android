package main

import (
	"strings"
	"testing"
	"time"
)

// 超时归因必须落在正确的 cause 上。
//
// 这两条 cause 决定客户端建议用户"查网线"还是"查 BIOS"，指错了比不说更糟 ——
// 用户会照着错的建议白折腾一晚上。而这两条 note 里都含"网卡"二字，
// 靠关键词匹配必然误判其中一条，所以中继必须给结构化字段。
func TestTimeoutCause(t *testing.T) {
	cases := []struct {
		name    string
		mac     string
		arpSeen time.Duration
		want    string
		wantIn  string
	}{
		{
			name:    "从头到尾没见过网卡 -> 网络侧",
			mac:     "AA:BB:CC:DD:EE:FF",
			arpSeen: -1,
			want:    causeNoARP,
			wantIn:  "没在 ARP 表里看到",
		},
		{
			name:    "网卡上电了但端口没起 -> 电脑侧",
			mac:     "AA:BB:CC:DD:EE:FF",
			arpSeen: 3200 * time.Millisecond,
			want:    causePortsUp,
			wantIn:  "上电了",
		},
		{
			name:    "只配了 host 没 MAC",
			mac:     "",
			arpSeen: -1,
			want:    causeNoMAC,
			wantIn:  "只配了 host",
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			j := &confirmJob{
				MAC:      c.mac,
				State:    stateWaiting,
				ARPSeen:  c.arpSeen,
				Started:  time.Now(),
				Deadline: time.Second,
			}
			j.finishTimeout()
			if j.State != stateTimeout {
				t.Fatalf("state = %q，想要 %q", j.State, stateTimeout)
			}
			if j.LastCause != c.want {
				t.Errorf("cause = %q，想要 %q", j.LastCause, c.want)
			}
			if !strings.Contains(j.LastNote, c.wantIn) {
				t.Errorf("note = %q，里面应该含 %q", j.LastNote, c.wantIn)
			}
			// 机器可读字段必须真的出现在 JSON 里，否则客户端拿不到。
			if v := j.view(); v.Cause != c.want {
				t.Errorf("view().Cause = %q，想要 %q", v.Cause, c.want)
			}
		})
	}
}

// 已经通了就不该被改成超时。
func TestFinishTimeoutKeepsOnline(t *testing.T) {
	j := &confirmJob{MAC: "AA:BB:CC:DD:EE:FF", State: stateOnline, ARPSeen: time.Second}
	j.finishTimeout()
	if j.State != stateOnline {
		t.Errorf("state = %q，通了就不该改", j.State)
	}
	if j.LastCause != "" {
		t.Errorf("cause = %q，通了不该有归因", j.LastCause)
	}
}

// 没超时的任务不该带 cause —— 带着会让客户端误以为已经出结论了。
func TestNoCauseWhileWaiting(t *testing.T) {
	j := &confirmJob{MAC: "AA:BB:CC:DD:EE:FF", State: stateWaiting, ARPSeen: -1}
	if v := j.view(); v.Cause != "" {
		t.Errorf("等待中 cause = %q，应为空", v.Cause)
	}
}
