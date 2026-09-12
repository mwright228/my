package main_test

import (
	"os"
	"testing"

	"github.com/mwright228/my/src/tbrutal/bridge"
)

func TestMain(m *testing.M) {
	bridge.SetSocketProtector(func(int) bool { return true })
	code := m.Run()
	bridge.SetSocketProtector(nil)
	os.Exit(code)
}
