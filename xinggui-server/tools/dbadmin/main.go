package main

import (
	"database/sql"
	"fmt"
	"os"

	_ "modernc.org/sqlite"
)

func main() {
	if len(os.Args) < 3 {
		fmt.Println("usage: tmpdb <db> <username> [status]")
		os.Exit(2)
	}
	path, user := os.Args[1], os.Args[2]
	status := "active"
	if len(os.Args) > 3 {
		status = os.Args[3]
	}
	db, err := sql.Open("sqlite", "file:"+path+"?_pragma=busy_timeout(5000)")
	if err != nil {
		fmt.Println("open:", err)
		os.Exit(1)
	}
	defer db.Close()
	res, err := db.Exec(`UPDATE users SET status=? WHERE username=? COLLATE NOCASE`, status, user)
	if err != nil {
		fmt.Println("update:", err)
		os.Exit(1)
	}
	n, _ := res.RowsAffected()
	fmt.Printf("updated=%d user=%s status=%s\n", n, user, status)
	var got string
	if err := db.QueryRow(`SELECT status FROM users WHERE username=? COLLATE NOCASE`, user).Scan(&got); err == nil {
		fmt.Println("now status =", got)
	}
}