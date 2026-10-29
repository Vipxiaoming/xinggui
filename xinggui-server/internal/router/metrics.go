package router

import (
	"time"

	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/disk"
)

var startTime = time.Now()

// cpuPercent 取最近 200ms 的整机 CPU 占用；失败时退化为进程 CPU 占比的近似值。
func cpuPercent() float64 {
	if v, err := cpu.Percent(200*time.Millisecond, false); err == nil && len(v) > 0 {
		return round1(v[0])
	}
	return 0
}

// diskPercent 取数据目录所在盘的占用率。
func diskPercent() float64 {
	if v, err := disk.Usage("."); err == nil {
		return round1(v.UsedPercent)
	}
	return 0
}

// dbPingMS 用一次极轻的查询测 DB 往返耗时。
func (s *Server) dbPingMS() float64 {
	t0 := time.Now()
	_, err := s.db.CountUsers("")
	if err != nil {
		return -1
	}
	return float64(time.Since(t0).Microseconds()) / 1000
}
