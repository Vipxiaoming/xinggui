package db

import (
	"database/sql"
	"encoding/json"
	"errors"
	"math"
	"strings"
	"time"
)

// ---------- 第二阶段扩展表 ----------

const schema2 = `
CREATE TABLE IF NOT EXISTS admins (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  username      TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash TEXT NOT NULL,
  totp_secret   TEXT NOT NULL DEFAULT '',
  role          TEXT NOT NULL DEFAULT 'superadmin',
  created_at    INTEGER NOT NULL,
  last_login_at INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS admin_tokens (
  jti        TEXT PRIMARY KEY,
  username   TEXT NOT NULL,
  issued_at  INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  revoked    INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS settings (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS versions (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  version_code     INTEGER NOT NULL,
  version_name     TEXT NOT NULL,
  url              TEXT NOT NULL DEFAULT '',
  sha256           TEXT NOT NULL DEFAULT '',
  notes            TEXT NOT NULL DEFAULT '',
  force            INTEGER NOT NULL DEFAULT 0,
  min_version_code INTEGER NOT NULL DEFAULT 1,
  published        INTEGER NOT NULL DEFAULT 1,
  created_at       INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS resolve_logs (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  username    TEXT NOT NULL DEFAULT '',
  source      TEXT NOT NULL DEFAULT '',
  resolved_by TEXT NOT NULL DEFAULT '',
  ok          INTEGER NOT NULL DEFAULT 0,
  code        TEXT NOT NULL DEFAULT '',
  ms          INTEGER NOT NULL DEFAULT 0,
  cached      INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_resolve_created ON resolve_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_resolve_platform ON resolve_logs(resolved_by);
CREATE TABLE IF NOT EXISTS error_logs (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  at         INTEGER NOT NULL,
  level      TEXT NOT NULL DEFAULT 'error',
  platform   TEXT NOT NULL DEFAULT '',
  code       TEXT NOT NULL DEFAULT '',
  message    TEXT NOT NULL DEFAULT '',
  username   TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_error_at ON error_logs(at);
CREATE TABLE IF NOT EXISTS favorites (
  username  TEXT NOT NULL COLLATE NOCASE,
  song_id   TEXT NOT NULL,
  platform  TEXT NOT NULL DEFAULT '',
  title     TEXT NOT NULL DEFAULT '',
  artist    TEXT NOT NULL DEFAULT '',
  album     TEXT NOT NULL DEFAULT '',
  duration  INTEGER NOT NULL DEFAULT 0,
  cover     TEXT NOT NULL DEFAULT '',
  available INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY(username, song_id)
);
CREATE TABLE IF NOT EXISTS playlists (
  username   TEXT NOT NULL COLLATE NOCASE,
  id         TEXT NOT NULL,
  name       TEXT NOT NULL DEFAULT '',
  is_public  INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY(username, id)
);
CREATE TABLE IF NOT EXISTS playlist_songs (
  username    TEXT NOT NULL COLLATE NOCASE,
  playlist_id TEXT NOT NULL,
  song_id     TEXT NOT NULL,
  title       TEXT NOT NULL DEFAULT '',
  artist      TEXT NOT NULL DEFAULT '',
  album       TEXT NOT NULL DEFAULT '',
  duration    INTEGER NOT NULL DEFAULT 0,
  platform    TEXT NOT NULL DEFAULT '',
  sort        INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(username, playlist_id, song_id)
);
CREATE TABLE IF NOT EXISTS history (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  username       TEXT NOT NULL COLLATE NOCASE,
  song_id        TEXT NOT NULL DEFAULT '',
  title          TEXT NOT NULL DEFAULT '',
  artist         TEXT NOT NULL DEFAULT '',
  platform       TEXT NOT NULL DEFAULT '',
  played_at      INTEGER NOT NULL,
  played_seconds INTEGER NOT NULL DEFAULT 0,
  duration       INTEGER NOT NULL DEFAULT 0,
  completed      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_history_user ON history(username, played_at);
CREATE TABLE IF NOT EXISTS downloads (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  username   TEXT NOT NULL COLLATE NOCASE,
  song_id    TEXT NOT NULL DEFAULT '',
  title      TEXT NOT NULL DEFAULT '',
  artist     TEXT NOT NULL DEFAULT '',
  quality    TEXT NOT NULL DEFAULT '',
  bytes      INTEGER NOT NULL DEFAULT 0,
  status     TEXT NOT NULL DEFAULT 'done',
  path       TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_downloads_user ON downloads(username, created_at);
CREATE TABLE IF NOT EXISTS sync_changes (
  seq        INTEGER PRIMARY KEY AUTOINCREMENT,
  username   TEXT NOT NULL COLLATE NOCASE,
  device_id  TEXT NOT NULL DEFAULT '',
  type       TEXT NOT NULL DEFAULT '',
  op         TEXT NOT NULL DEFAULT '',
  obj_id     TEXT NOT NULL DEFAULT '',
  payload    TEXT NOT NULL DEFAULT '{}',
  updated_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sync_user ON sync_changes(username, seq);
`

// migrateExtra 建扩展表并补齐 users 缺列（幂等）。
func migrateExtra(sdb *sql.DB) error {
	if _, err := sdb.Exec(schema2); err != nil {
		return err
	}
	for _, col := range []struct{ name, ddl string }{
		{"email", "ALTER TABLE users ADD COLUMN email TEXT NOT NULL DEFAULT ''"},
		{"note", "ALTER TABLE users ADD COLUMN note TEXT NOT NULL DEFAULT ''"},
		{"last_login_at", "ALTER TABLE users ADD COLUMN last_login_at INTEGER NOT NULL DEFAULT 0"},
	} {
		exists, err := columnExists(sdb, "users", col.name)
		if err != nil {
			return err
		}
		if !exists {
			if _, err := sdb.Exec(col.ddl); err != nil {
				return err
			}
		}
	}
	return nil
}

func columnExists(sdb *sql.DB, table, col string) (bool, error) {
	rows, err := sdb.Query(`PRAGMA table_info(` + table + `)`)
	if err != nil {
		return false, err
	}
	defer rows.Close()
	for rows.Next() {
		var (
			cid     int
			name    string
			typ     string
			notnull int
			dflt    any
			pk      int
		)
		if err := rows.Scan(&cid, &name, &typ, &notnull, &dflt, &pk); err != nil {
			return false, err
		}
		if strings.EqualFold(name, col) {
			return true, nil
		}
	}
	return false, rows.Err()
}

// ---------- 管理员 ----------

type Admin struct {
	ID          int64
	Username    string
	PasswordHash string
	TOTPSecret  string
	Role        string
	CreatedAt   int64
	LastLoginAt int64
}

func (d *DB) CountAdmins() (int, error) {
	var n int
	err := d.sql.QueryRow(`SELECT COUNT(*) FROM admins`).Scan(&n)
	return n, err
}

func (d *DB) GetAdmin(username string) (*Admin, error) {
	var a Admin
	err := d.sql.QueryRow(`SELECT id,username,password_hash,totp_secret,role,created_at,last_login_at FROM admins WHERE username=? COLLATE NOCASE`, username).
		Scan(&a.ID, &a.Username, &a.PasswordHash, &a.TOTPSecret, &a.Role, &a.CreatedAt, &a.LastLoginAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &a, nil
}

func (d *DB) CreateAdmin(username, hash, totp, role string) error {
	_, err := d.sql.Exec(`INSERT INTO admins(username,password_hash,totp_secret,role,created_at) VALUES(?,?,?,?,?)`,
		username, hash, totp, role, now())
	return err
}

func (d *DB) TouchAdminLogin(username string) {
	_, _ = d.sql.Exec(`UPDATE admins SET last_login_at=? WHERE username=? COLLATE NOCASE`, now(), username)
}

func (d *DB) AddAdminToken(jti, username string, issued, expires int64) error {
	_, err := d.sql.Exec(`INSERT INTO admin_tokens(jti,username,issued_at,expires_at) VALUES(?,?,?,?)`, jti, username, issued, expires)
	return err
}

func (d *DB) RevokeAdminToken(jti string) error {
	_, err := d.sql.Exec(`UPDATE admin_tokens SET revoked=1 WHERE jti=?`, jti)
	return err
}

func (d *DB) AdminTokenValid(jti string) (bool, error) {
	var revoked int
	var exp int64
	err := d.sql.QueryRow(`SELECT revoked,expires_at FROM admin_tokens WHERE jti=?`, jti).Scan(&revoked, &exp)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return revoked == 0 && exp > now(), nil
}

// ---------- 设置 KV ----------

func (d *DB) GetSetting(key string) (string, bool, error) {
	var v string
	err := d.sql.QueryRow(`SELECT v FROM settings WHERE k=?`, key).Scan(&v)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return v, true, nil
}

func (d *DB) SetSetting(key, value string) error {
	_, err := d.sql.Exec(`INSERT INTO settings(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v`, key, value)
	return err
}

// ---------- 版本 ----------

type Version struct {
	ID             int64
	VersionCode    int
	VersionName    string
	URL            string
	SHA256         string
	Notes          string
	Force          bool
	MinVersionCode int
	Published      bool
	CreatedAt      int64
}

func (d *DB) LatestVersion() (*Version, error) {
	var v Version
	var force, pub int
	err := d.sql.QueryRow(`SELECT id,version_code,version_name,url,sha256,notes,force,min_version_code,published,created_at
		FROM versions WHERE published=1 ORDER BY version_code DESC LIMIT 1`).
		Scan(&v.ID, &v.VersionCode, &v.VersionName, &v.URL, &v.SHA256, &v.Notes, &force, &v.MinVersionCode, &pub, &v.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	v.Force = force == 1
	v.Published = pub == 1
	return &v, nil
}

func (d *DB) UpsertVersion(v Version) error {
	force, pub := 0, 0
	if v.Force {
		force = 1
	}
	if v.Published {
		pub = 1
	}
	var id int64
	err := d.sql.QueryRow(`SELECT id FROM versions WHERE version_code=?`, v.VersionCode).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		_, err = d.sql.Exec(`INSERT INTO versions(version_code,version_name,url,sha256,notes,force,min_version_code,published,created_at)
			VALUES(?,?,?,?,?,?,?,?,?)`, v.VersionCode, v.VersionName, v.URL, v.SHA256, v.Notes, force, v.MinVersionCode, pub, now())
		return err
	}
	if err != nil {
		return err
	}
	_, err = d.sql.Exec(`UPDATE versions SET version_name=?,url=?,sha256=?,notes=?,force=?,min_version_code=?,published=? WHERE id=?`,
		v.VersionName, v.URL, v.SHA256, v.Notes, force, v.MinVersionCode, pub, id)
	return err
}

// ---------- 解析日志 / 错误日志 ----------

func (d *DB) LogResolve(username, source, resolvedBy string, ok bool, code string, ms int64, cached bool) {
	o, c := 0, 0
	if ok {
		o = 1
	}
	if cached {
		c = 1
	}
	_, _ = d.sql.Exec(`INSERT INTO resolve_logs(username,source,resolved_by,ok,code,ms,cached,created_at) VALUES(?,?,?,?,?,?,?,?)`,
		username, source, resolvedBy, o, code, ms, c, now())
}

func (d *DB) LogError(level, platform, code, message, username string) {
	_, _ = d.sql.Exec(`INSERT INTO error_logs(at,level,platform,code,message,username) VALUES(?,?,?,?,?,?)`,
		now(), level, platform, code, message, username)
}

type ResolveLog struct {
	ID         int64
	Username   string
	Source     string
	ResolvedBy string
	OK         bool
	Code       string
	MS         int64
	Cached     bool
	CreatedAt  int64
}

type ErrorLog struct {
	ID       int64
	At       int64
	Level    string
	Platform string
	Code     string
	Message  string
	Username string
}

func (d *DB) RecentErrors(limit, offset int, level, platform, q string) ([]ErrorLog, int, error) {
	where := []string{"1=1"}
	args := []any{}
	if level != "" {
		where = append(where, "level=?")
		args = append(args, level)
	}
	if platform != "" {
		where = append(where, "platform=?")
		args = append(args, platform)
	}
	if q != "" {
		where = append(where, "(code LIKE ? OR message LIKE ? OR username LIKE ?)")
		like := "%" + q + "%"
		args = append(args, like, like, like)
	}
	cond := strings.Join(where, " AND ")
	var total int
	if err := d.sql.QueryRow(`SELECT COUNT(*) FROM error_logs WHERE `+cond, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	if limit <= 0 || limit > 500 {
		limit = 500
	}
	rows, err := d.sql.Query(`SELECT id,at,level,platform,code,message,username FROM error_logs WHERE `+cond+` ORDER BY at DESC LIMIT ? OFFSET ?`,
		append(args, limit, offset)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := make([]ErrorLog, 0, limit)
	for rows.Next() {
		var e ErrorLog
		if err := rows.Scan(&e.ID, &e.At, &e.Level, &e.Platform, &e.Code, &e.Message, &e.Username); err != nil {
			return nil, 0, err
		}
		out = append(out, e)
	}
	return out, total, rows.Err()
}

// ResolveStats 返回近 since 秒内各平台的成功率与耗时。
type PlatformStat struct {
	Platform string
	Total    int
	OK       int
	P50      int64
	P95      int64
}

func (d *DB) ResolveStats(since int64) ([]PlatformStat, error) {
	rows, err := d.sql.Query(`SELECT resolved_by, ok, ms FROM resolve_logs WHERE created_at>=? AND resolved_by<>''`, since)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	type agg struct {
		total, ok int
		ms        []int64
	}
	m := map[string]*agg{}
	for rows.Next() {
		var p string
		var ok int
		var ms int64
		if err := rows.Scan(&p, &ok, &ms); err != nil {
			return nil, err
		}
		a := m[p]
		if a == nil {
			a = &agg{}
			m[p] = a
		}
		a.total++
		if ok == 1 {
			a.ok++
		}
		a.ms = append(a.ms, ms)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := make([]PlatformStat, 0, len(m))
	for p, a := range m {
		sortInt64(a.ms)
		out = append(out, PlatformStat{
			Platform: p, Total: a.total, OK: a.ok,
			P50: percentile(a.ms, 0.50), P95: percentile(a.ms, 0.95),
		})
	}
	return out, nil
}

func sortInt64(v []int64) {
	for i := 1; i < len(v); i++ {
		for j := i; j > 0 && v[j] < v[j-1]; j-- {
			v[j], v[j-1] = v[j-1], v[j]
		}
	}
}

// percentile 用最近秩法并四舍五入，避免 10 个样本的 p95 落回 p90。
func percentile(sorted []int64, p float64) int64 {
	if len(sorted) == 0 {
		return 0
	}
	// 最近秩法：idx = ceil(p*n) - 1，p50/p95 都取到真实样本值
	idx := int(math.Ceil(p*float64(len(sorted)))) - 1
	if idx < 0 {
		idx = 0
	}
	if idx >= len(sorted) {
		idx = len(sorted) - 1
	}
	return sorted[idx]
}

// CountUserResolves 统计单个用户的解析次数（管理页用户详情用）。
func (d *DB) CountUserResolves(username string) (int, error) {
	var n int
	err := d.sql.QueryRow(`SELECT COUNT(*) FROM resolve_logs WHERE username=? COLLATE NOCASE`, username).Scan(&n)
	return n, err
}

func (d *DB) CountResolvesSince(since int64) (int, error) {
	var n int
	err := d.sql.QueryRow(`SELECT COUNT(*) FROM resolve_logs WHERE created_at>=?`, since).Scan(&n)
	return n, err
}

func (d *DB) ResolveTrend(days int) ([]int, error) {
	out := make([]int, days)
	start := time.Now().AddDate(0, 0, -(days - 1))
	dayStart := time.Date(start.Year(), start.Month(), start.Day(), 0, 0, 0, 0, start.Location()).Unix()
	for i := 0; i < days; i++ {
		from := dayStart + int64(i)*86400
		var n int
		if err := d.sql.QueryRow(`SELECT COUNT(*) FROM resolve_logs WHERE created_at>=? AND created_at<?`, from, from+86400).Scan(&n); err != nil {
			return nil, err
		}
		out[i] = n
	}
	return out, nil
}

func (d *DB) TopSearches(limit int) ([]map[string]any, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	rows, err := d.sql.Query(`SELECT keyword, COUNT(*) c FROM search_logs GROUP BY keyword ORDER BY c DESC LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]map[string]any, 0, limit)
	for rows.Next() {
		var k string
		var c int
		if err := rows.Scan(&k, &c); err != nil {
			return nil, err
		}
		out = append(out, map[string]any{"keyword": k, "count": c})
	}
	return out, rows.Err()
}

// ---------- 用户管理扩展 ----------

type UserFull struct {
	User
	Email       string
	Note        string
	LastLoginAt int64
	Deleted     bool
}

func (d *DB) GetUserFull(username string) (*UserFull, error) {
	var u UserFull
	var del int
	err := d.sql.QueryRow(`SELECT id,username,password_hash,status,vip_type,expire_at,max_devices,token_version,created_at,
		email,note,last_login_at,deleted FROM users WHERE username=? COLLATE NOCASE`, username).
		Scan(&u.ID, &u.Username, &u.PasswordHash, &u.Status, &u.VipType, &u.ExpireAt, &u.MaxDevices,
			&u.TokenVersion, &u.CreatedAt, &u.Email, &u.Note, &u.LastLoginAt, &del)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	u.Deleted = del == 1
	return &u, nil
}

type UserRow struct {
	Username     string
	Email        string
	Status       string
	VipType      string
	ExpireAt     int64
	MaxDevices   int
	CreatedAt    int64
	LastActiveAt int64
	ResolveCount int
	Note         string
}

func (d *DB) ListUsers(q, status string, page, pageSize int, includeDeleted bool) ([]UserRow, int, error) {
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 200 {
		pageSize = 20
	}
	where := []string{"1=1"}
	args := []any{}
	if !includeDeleted {
		where = append(where, "u.deleted=0")
	}
	if q != "" {
		where = append(where, "(u.username LIKE ? OR u.email LIKE ?)")
		like := "%" + q + "%"
		args = append(args, like, like)
	}
	if status != "" {
		where = append(where, "u.status=?")
		args = append(args, status)
	}
	cond := strings.Join(where, " AND ")
	var total int
	if err := d.sql.QueryRow(`SELECT COUNT(*) FROM users u WHERE `+cond, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := d.sql.Query(`SELECT u.username,u.email,u.status,u.vip_type,u.expire_at,u.max_devices,u.created_at,u.note,
		COALESCE((SELECT MAX(last_seen_at) FROM devices d WHERE d.username=u.username AND d.revoked=0),0) la,
		COALESCE((SELECT COUNT(*) FROM resolve_logs r WHERE r.username=u.username),0) rc
		FROM users u WHERE `+cond+` ORDER BY u.created_at DESC LIMIT ? OFFSET ?`,
		append(args, pageSize, (page-1)*pageSize)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := make([]UserRow, 0, pageSize)
	for rows.Next() {
		var r UserRow
		if err := rows.Scan(&r.Username, &r.Email, &r.Status, &r.VipType, &r.ExpireAt, &r.MaxDevices, &r.CreatedAt, &r.Note, &r.LastActiveAt, &r.ResolveCount); err != nil {
			return nil, 0, err
		}
		out = append(out, r)
	}
	return out, total, rows.Err()
}

func (d *DB) UpdateUserPassword(username, hash string) error {
	res, err := d.sql.Exec(`UPDATE users SET password_hash=?, token_version=token_version+1 WHERE username=? COLLATE NOCASE AND deleted=0`, hash, username)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func (d *DB) SetUserExpire(username string, expireAt int64) error {
	_, err := d.sql.Exec(`UPDATE users SET expire_at=? WHERE username=? COLLATE NOCASE AND deleted=0`, expireAt, username)
	return err
}

func (d *DB) SoftDeleteUser(username string) error {
	res, err := d.sql.Exec(`UPDATE users SET deleted=1, status='deleted', token_version=token_version+1 WHERE username=? COLLATE NOCASE AND deleted=0`, username)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func (d *DB) RevokeAllDevices(username string) error {
	_, err := d.sql.Exec(`UPDATE devices SET revoked=1 WHERE username=? COLLATE NOCASE`, username)
	return err
}

func (d *DB) TouchUserLogin(username string) {
	_, _ = d.sql.Exec(`UPDATE users SET last_login_at=? WHERE username=? COLLATE NOCASE`, now(), username)
}

func (d *DB) CountUsers(status string) (int, error) {
	var n int
	if status == "" {
		return n, d.sql.QueryRow(`SELECT COUNT(*) FROM users WHERE deleted=0`).Scan(&n)
	}
	return n, d.sql.QueryRow(`SELECT COUNT(*) FROM users WHERE deleted=0 AND status=?`, status).Scan(&n)
}

func (d *DB) CountUsersSince(since int64) (int, error) {
	var n int
	return n, d.sql.QueryRow(`SELECT COUNT(*) FROM users WHERE deleted=0 AND created_at>=?`, since).Scan(&n)
}

func (d *DB) CountActiveDevices(since int64) (int, error) {
	var n int
	return n, d.sql.QueryRow(`SELECT COUNT(*) FROM devices WHERE revoked=0 AND last_seen_at>=?`, since).Scan(&n)
}

// ---------- 用户数据浏览 ----------

func (d *DB) Favorites(username string) ([]map[string]any, error) {
	rows, err := d.sql.Query(`SELECT song_id,platform,title,artist,album,duration,cover,available,created_at FROM favorites
		WHERE username=? COLLATE NOCASE ORDER BY created_at DESC`, username)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id, p, t, a, al, cover string
		var dur int
		var avail int
		var created int64
		if err := rows.Scan(&id, &p, &t, &a, &al, &dur, &cover, &avail, &created); err != nil {
			return nil, err
		}
		out = append(out, map[string]any{
			"id": id, "songId": id, "platform": p, "title": t, "artist": a, "album": al,
			"duration": dur, "cover": cover, "available": avail == 1, "createdAt": fmtTime(created),
		})
	}
	return out, rows.Err()
}

func (d *DB) Playlists(username string) ([]map[string]any, error) {
	rows, err := d.sql.Query(`SELECT id,name,is_public,created_at,updated_at FROM playlists WHERE username=? COLLATE NOCASE ORDER BY created_at DESC`, username)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	type pl struct {
		id, name string
		pub      int
		created  int64
		updated  int64
	}
	var list []pl
	for rows.Next() {
		var p pl
		if err := rows.Scan(&p.id, &p.name, &p.pub, &p.created, &p.updated); err != nil {
			return nil, err
		}
		list = append(list, p)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := make([]map[string]any, 0, len(list))
	for _, p := range list {
		songs, err := d.PlaylistSongs(username, p.id)
		if err != nil {
			return nil, err
		}
		out = append(out, map[string]any{
			"id": p.id, "name": p.name, "isPublic": p.pub == 1,
			"createdAt": fmtTime(p.created), "updatedAt": fmtTime(p.updated), "songs": songs,
		})
	}
	return out, nil
}

func (d *DB) PlaylistSongs(username, playlistID string) ([]map[string]any, error) {
	rows, err := d.sql.Query(`SELECT song_id,title,artist,album,duration,platform FROM playlist_songs
		WHERE username=? COLLATE NOCASE AND playlist_id=? ORDER BY sort, rowid`, username, playlistID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id, t, a, al, p string
		var dur int
		if err := rows.Scan(&id, &t, &a, &al, &dur, &p); err != nil {
			return nil, err
		}
		out = append(out, map[string]any{"id": id, "songId": id, "title": t, "artist": a, "album": al, "duration": dur, "platform": p})
	}
	return out, rows.Err()
}

func (d *DB) History(username string, limit int) ([]map[string]any, error) {
	if limit <= 0 || limit > 500 {
		limit = 200
	}
	rows, err := d.sql.Query(`SELECT song_id,title,artist,platform,played_at,played_seconds,duration,completed
		FROM history WHERE username=? COLLATE NOCASE ORDER BY played_at DESC LIMIT ?`, username, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id, t, a, p string
		var at int64
		var ps, dur, comp int
		if err := rows.Scan(&id, &t, &a, &p, &at, &ps, &dur, &comp); err != nil {
			return nil, err
		}
		out = append(out, map[string]any{
			"id": id, "songId": id, "title": t, "artist": a, "platform": p,
			"playedAt": fmtTime(at), "playedSeconds": ps, "duration": dur, "completed": comp == 1,
		})
	}
	return out, rows.Err()
}

func (d *DB) Downloads(username string) ([]map[string]any, error) {
	rows, err := d.sql.Query(`SELECT song_id,title,artist,quality,bytes,status,path,created_at FROM downloads
		WHERE username=? COLLATE NOCASE ORDER BY created_at DESC`, username)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id, t, a, q, st, path string
		var b int64
		var created int64
		if err := rows.Scan(&id, &t, &a, &q, &b, &st, &path, &created); err != nil {
			return nil, err
		}
		out = append(out, map[string]any{
			"id": id, "songId": id, "title": t, "artist": a, "quality": q,
			"bytes": b, "status": st, "path": path, "createdAt": fmtTime(created),
		})
	}
	return out, rows.Err()
}

func (d *DB) Searches(username string, limit int) ([]map[string]any, error) {
	if limit <= 0 || limit > 500 {
		limit = 200
	}
	rows, err := d.sql.Query(`SELECT keyword, created_at FROM search_logs WHERE username=? COLLATE NOCASE ORDER BY created_at DESC LIMIT ?`, username, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var k string
		var at int64
		if err := rows.Scan(&k, &at); err != nil {
			return nil, err
		}
		out = append(out, map[string]any{"keyword": k, "at": fmtTime(at), "results": 0})
	}
	return out, rows.Err()
}

// ---------- 同步 ----------

type SyncChange struct {
	Seq       int64
	Type      string
	Op        string
	ID        string
	Payload   string
	UpdatedAt int64
}

func (d *DB) AppendSyncChange(username, deviceID, typ, op, id, payload string, updatedAt int64) error {
	_, err := d.sql.Exec(`INSERT INTO sync_changes(username,device_id,type,op,obj_id,payload,updated_at,created_at)
		VALUES(?,?,?,?,?,?,?,?)`, username, deviceID, typ, op, id, payload, updatedAt, now())
	return err
}

func (d *DB) SyncChangesSince(username string, since int64, limit int) ([]SyncChange, int64, error) {
	if limit <= 0 || limit > 2000 {
		limit = 500
	}
	rows, err := d.sql.Query(`SELECT seq,type,op,obj_id,payload,updated_at FROM sync_changes
		WHERE username=? COLLATE NOCASE AND seq>? ORDER BY seq LIMIT ?`, username, since, limit)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []SyncChange{}
	var maxSeq int64
	for rows.Next() {
		var c SyncChange
		if err := rows.Scan(&c.Seq, &c.Type, &c.Op, &c.ID, &c.Payload, &c.UpdatedAt); err != nil {
			return nil, 0, err
		}
		if c.Seq > maxSeq {
			maxSeq = c.Seq
		}
		out = append(out, c)
	}
	return out, maxSeq, rows.Err()
}

func (d *DB) UpsertFavorite(username string, m map[string]any) error {
	_, err := d.sql.Exec(`INSERT INTO favorites(username,song_id,platform,title,artist,album,duration,cover,available,created_at,updated_at)
		VALUES(?,?,?,?,?,?,?,?,?,?,?)
		ON CONFLICT(username,song_id) DO UPDATE SET platform=excluded.platform,title=excluded.title,artist=excluded.artist,
		album=excluded.album,duration=excluded.duration,cover=excluded.cover,available=excluded.available,updated_at=excluded.updated_at`,
		username, str(m, "songId"), str(m, "platform"), str(m, "title"), str(m, "artist"), str(m, "album"),
		num(m, "duration"), str(m, "cover"), 1, now(), now())
	return err
}

func (d *DB) DeleteFavorite(username, songID string) error {
	_, err := d.sql.Exec(`DELETE FROM favorites WHERE username=? COLLATE NOCASE AND song_id=?`, username, songID)
	return err
}

func (d *DB) UpsertPlaylist(username, id, name string, isPublic bool, songs []map[string]any) error {
	pub := 0
	if isPublic {
		pub = 1
	}
	_, err := d.sql.Exec(`INSERT INTO playlists(username,id,name,is_public,created_at,updated_at) VALUES(?,?,?,?,?,?)
		ON CONFLICT(username,id) DO UPDATE SET name=excluded.name,is_public=excluded.is_public,updated_at=excluded.updated_at`,
		username, id, name, pub, now(), now())
	if err != nil {
		return err
	}
	if songs == nil {
		return nil
	}
	if _, err := d.sql.Exec(`DELETE FROM playlist_songs WHERE username=? COLLATE NOCASE AND playlist_id=?`, username, id); err != nil {
		return err
	}
	for i, s := range songs {
		if _, err := d.sql.Exec(`INSERT INTO playlist_songs(username,playlist_id,song_id,title,artist,album,duration,platform,sort)
			VALUES(?,?,?,?,?,?,?,?,?)`, username, id, str(s, "songId"), str(s, "title"), str(s, "artist"),
			str(s, "album"), num(s, "duration"), str(s, "platform"), i); err != nil {
			return err
		}
	}
	return nil
}

func (d *DB) AppendHistory(username string, m map[string]any) error {
	_, err := d.sql.Exec(`INSERT INTO history(username,song_id,title,artist,platform,played_at,played_seconds,duration,completed)
		VALUES(?,?,?,?,?,?,?,?,?)`, username, str(m, "songId"), str(m, "title"), str(m, "artist"), str(m, "platform"),
		now(), num(m, "playedSeconds"), num(m, "duration"), boolInt(m, "completed"))
	return err
}

func str(m map[string]any, k string) string {
	if v, ok := m[k]; ok {
		if s, ok := v.(string); ok {
			return s
		}
	}
	return ""
}

func num(m map[string]any, k string) int {
	if v, ok := m[k]; ok {
		switch t := v.(type) {
		case float64:
			return int(t)
		case int:
			return t
		case int64:
			return int(t)
		}
	}
	return 0
}

func boolInt(m map[string]any, k string) int {
	if v, ok := m[k].(bool); ok && v {
		return 1
	}
	return 0
}

func fmtTime(ts int64) string {
	if ts <= 0 {
		return ""
	}
	return time.Unix(ts, 0).Format("2006-01-02 15:04")
}

// DecodeJSON 是给 sync 载荷用的小工具。
func DecodeJSON(s string, v any) error {
	if s == "" {
		return nil
	}
	return json.Unmarshal([]byte(s), v)
}
