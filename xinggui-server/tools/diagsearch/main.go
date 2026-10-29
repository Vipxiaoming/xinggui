package main

import (
	"context"
	"fmt"

	"xinggui/internal/resolve"
)

func main() {
	r := resolve.NewResolver()
	ok, fail := 0, 0
	for i := 0; i < 12; i++ {
		_, err := r.Search(context.Background(), "晴天", "周杰伦", 20)
		if err != nil {
			fail++
			fmt.Printf("[%02d] FAIL %v\n", i, err)
		} else {
			ok++
			fmt.Printf("[%02d] OK\n", i)
		}
	}
	fmt.Printf("ok=%d fail=%d\n", ok, fail)
}