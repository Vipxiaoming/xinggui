package main

import (
	"fmt"
	"os"
	"time"

	"xinggui/internal/auth"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: totpgen <secret>")
		return
	}
	c, err := auth.TOTPCode(os.Args[1], time.Now())
	if err != nil {
		fmt.Println("err:", err)
		return
	}
	fmt.Println(c)
}