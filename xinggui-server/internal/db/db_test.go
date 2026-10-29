package db

import (
	"path/filepath"
	"testing"
)

func newTestDB(t *testing.T) *DB {
	t.Helper()
	d, err := Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	t.Cleanup(func() { d.Close() })
	return d
}

func TestMigrateExtraIdempotent(t *testing.T) {
	d := newTestDB(t)
	// Open 内部已执行一次；这里再跑一次，验证幂等（不会因重复 ALTER 报错）
	if err := migrateExtra(d.sql); err != nil {
		t.Fatalf("second migrate: %v", err)
	}
	for _, col := range []string{"email", "note", "last_login_at"} {
		ok, err := columnExists(d.sql, "users", col)
		if err != nil || !ok {
			t.Errorf("users.%s 缺失 (err=%v)", col, err)
		}
	}
	for _, tbl := range []string{"admins", "admin_tokens", "settings", "versions",
		"resolve_logs", "error_logs", "favorites", "playlists", "playlist_songs",
		"history", "downloads", "sync_changes"} {
		var n int
		err := d.sql.QueryRow(`SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?`, tbl).Scan(&n)
		if err != nil || n != 1 {
			t.Errorf("表 %s 未创建 (err=%v)", tbl, err)
		}
	}
}

func TestAdminTokenLifecycle(t *testing.T) {
	d := newTestDB(t)
	if err := d.AddAdminToken("jti-1", "admin", now()-10, now()+3600); err != nil {
		t.Fatal(err)
	}
	ok, err := d.AdminTokenValid("jti-1")
	if err != nil || !ok {
		t.Fatalf("有效令牌应通过: ok=%v err=%v", ok, err)
	}
	if ok, _ := d.AdminTokenValid("nope"); ok {
		t.Error("不存在的令牌不应通过")
	}
	if err := d.RevokeAdminToken("jti-1"); err != nil {
		t.Fatal(err)
	}
	if ok, _ := d.AdminTokenValid("jti-1"); ok {
		t.Error("已吊销令牌不应通过")
	}
	// 过期令牌
	_ = d.AddAdminToken("jti-exp", "admin", now()-7200, now()-3600)
	if ok, _ := d.AdminTokenValid("jti-exp"); ok {
		t.Error("过期令牌不应通过")
	}
}

func TestPercentile(t *testing.T) {
	v := []int64{10, 20, 30, 40, 50, 60, 70, 80, 90, 100}
	if got := percentile(v, 0.5); got != 50 {
		t.Errorf("p50=%d want 50", got)
	}
	if got := percentile(v, 0.95); got != 100 {
		t.Errorf("p95=%d want 100", got)
	}
	if got := percentile(nil, 0.5); got != 0 {
		t.Errorf("空切片应返回 0，实际 %d", got)
	}
}

func TestVersionUpsert(t *testing.T) {
	d := newTestDB(t)
	v := Version{VersionCode: 10200, VersionName: "1.2.0", URL: "https://x/a.apk", SHA256: "abc", Notes: "n", MinVersionCode: 100, Published: true}
	if err := d.UpsertVersion(v); err != nil {
		t.Fatal(err)
	}
	got, err := d.LatestVersion()
	if err != nil || got.VersionName != "1.2.0" {
		t.Fatalf("回读失败: %v %+v", err, got)
	}
	// 同 version_code 应更新而不是重复插入
	v.VersionName = "1.2.0-hotfix"
	if err := d.UpsertVersion(v); err != nil {
		t.Fatal(err)
	}
	got2, _ := d.LatestVersion()
	if got2.VersionName != "1.2.0-hotfix" {
		t.Errorf("未按 version_code 更新: %s", got2.VersionName)
	}
	var n int
	_ = d.sql.QueryRow(`SELECT COUNT(*) FROM versions WHERE version_code=10200`).Scan(&n)
	if n != 1 {
		t.Errorf("重复插入，行数=%d", n)
	}
}

func TestUserLifecycleAndData(t *testing.T) {
	d := newTestDB(t)
	if err := d.CreateUser("alice", "hash"); err != nil {
		t.Fatal(err)
	}
	if err := d.SetStatus("alice", "active"); err != nil {
		t.Fatal(err)
	}
	if err := d.UpdateUserPassword("alice", "hash2"); err != nil {
		t.Fatal(err)
	}
	u, err := d.GetUserFull("alice")
	if err != nil {
		t.Fatal(err)
	}
	if u.PasswordHash != "hash2" || u.TokenVersion != 2 {
		t.Errorf("密码/token_version 未更新: %+v", u)
	}

	_ = d.UpsertFavorite("alice", map[string]any{"songId": "s1", "title": "t", "artist": "a", "platform": "wy", "duration": 200})
	favs, _ := d.Favorites("alice")
	if len(favs) != 1 {
		t.Fatalf("收藏数=%d", len(favs))
	}
	_ = d.DeleteFavorite("alice", "s1")
	favs, _ = d.Favorites("alice")
	if len(favs) != 0 {
		t.Error("删除收藏失败")
	}

	_ = d.UpsertPlaylist("alice", "p1", "歌单", true, []map[string]any{{"songId": "s1", "title": "t"}})
	pls, _ := d.Playlists("alice")
	if len(pls) != 1 || len(pls[0]["songs"].([]map[string]any)) != 1 {
		t.Fatalf("歌单异常: %+v", pls)
	}

	if err := d.AppendSyncChange("alice", "d1", "favorite", "add", "s1", "{}", 1); err != nil {
		t.Fatal(err)
	}
	ch, maxSeq, err := d.SyncChangesSince("alice", 0, 100)
	if err != nil || len(ch) != 1 || maxSeq != ch[0].Seq {
		t.Fatalf("sync 异常: %v %+v", err, ch)
	}
}

func TestListUsersFilter(t *testing.T) {
	d := newTestDB(t)
	for _, name := range []string{"u1", "u2", "u3"} {
		if err := d.CreateUser(name, "h"); err != nil {
			t.Fatal(err)
		}
	}
	if err := d.SetStatus("u2", "active"); err != nil {
		t.Fatal(err)
	}
	_, total, err := d.ListUsers("", "pending", 1, 10, false)
	if err != nil || total != 2 {
		t.Errorf("pending 计数=%d want 2 (err=%v)", total, err)
	}
	_, total, _ = d.ListUsers("u2", "", 1, 10, false)
	if total != 1 {
		t.Errorf("搜索 u2 命中=%d want 1", total)
	}
	if err := d.SoftDeleteUser("u3"); err != nil {
		t.Fatal(err)
	}
	_, total, _ = d.ListUsers("", "", 1, 10, false)
	if total != 2 {
		t.Errorf("软删除后默认列表=%d want 2", total)
	}
	_, total, _ = d.ListUsers("", "", 1, 10, true)
	if total != 3 {
		t.Errorf("includeDeleted 列表=%d want 3", total)
	}
}

func TestCountAdmins(t *testing.T) {
	d := newTestDB(t)
	if n, _ := d.CountAdmins(); n != 0 {
		t.Errorf("初始管理员数=%d", n)
	}
	if err := d.CreateAdmin("admin", "h", "SECRET", "superadmin"); err != nil {
		t.Fatal(err)
	}
	if n, _ := d.CountAdmins(); n != 1 {
		t.Errorf("管理员数=%d want 1", n)
	}
	a, err := d.GetAdmin("ADMIN")
	if err != nil || a.Username != "admin" {
		t.Errorf("大小写不敏感查询失败: %v %+v", err, a)
	}
}