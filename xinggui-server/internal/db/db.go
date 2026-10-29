package db

import (
	"database/sql"
	"errors"
	"time"

	_ "modernc.org/sqlite"
)

var ErrNotFound = errors.New("not found")

type DB struct{ sql *sql.DB }

type User struct {
	ID           int64
	Username     string
	PasswordHash string
	Status       string
	VipType      string
	ExpireAt     int64
	MaxDevices   int
	TokenVersion int
	CreatedAt    int64
}

type Device struct {
	DeviceID   string
	DeviceName string
	CreatedAt  int64
	LastSeenAt int64
}

const schema = `
CREATE TABLE IF NOT EXISTS users (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  username       TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash  TEXT NOT NULL,
  status         TEXT NOT NULL DEFAULT 'pending',
  vip_type       TEXT NOT NULL DEFAULT 'FREE',
  expire_at      INTEGER NOT NULL DEFAULT 0,
  max_devices    INTEGER NOT NULL DEFAULT 2,
  token_version  INTEGER NOT NULL DEFAULT 1,
  created_at     INTEGER NOT NULL,
  deleted        INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS devices (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  username    TEXT NOT NULL COLLATE NOCASE,
  device_id   TEXT NOT NULL,
  device_name TEXT NOT NULL DEFAULT '',
  created_at  INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  revoked     INTEGER NOT NULL DEFAULT 0,
  UNIQUE(username, device_id)
);
CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(username);
CREATE TABLE IF NOT EXISTS search_logs (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  username   TEXT NOT NULL COLLATE NOCASE,
  keyword    TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_search_logs_user ON search_logs(username);
CREATE INDEX IF NOT EXISTS idx_search_logs_created ON search_logs(created_at);
`

func Open(path string) (*DB, error) {
	dsn := "file:" + path + "?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)"
	sdb, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	sdb.SetMaxOpenConns(1)
	if err := sdb.Ping(); err != nil {
		return nil, err
	}
	if _, err := sdb.Exec(schema); err != nil {
		return nil, err
	}
	if err := migrateExtra(sdb); err != nil {
		return nil, err
	}
	return &DB{sql: sdb}, nil
}

func (d *DB) Close() error { return d.sql.Close() }

func now() int64 { return time.Now().Unix() }

func (d *DB) CreateUser(username, hash string) error {
	_, err := d.sql.Exec(
		`INSERT INTO users(username, password_hash, status, vip_type, expire_at, max_devices, token_version, created_at)
		 VALUES(?,?,'pending','FREE',0,2,1,?)`, username, hash, now())
	return err
}

func scanUser(row *sql.Row) (*User, error) {
	var u User
	err := row.Scan(&u.ID, &u.Username, &u.PasswordHash, &u.Status, &u.VipType, &u.ExpireAt, &u.MaxDevices, &u.TokenVersion, &u.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &u, nil
}

func (d *DB) GetUser(username string) (*User, error) {
	row := d.sql.QueryRow(`SELECT id,username,password_hash,status,vip_type,expire_at,max_devices,token_version,created_at
		FROM users WHERE username=? COLLATE NOCASE AND deleted=0`, username)
	return scanUser(row)
}

func (d *DB) SetStatus(username, status string) error {
	res, err := d.sql.Exec(`UPDATE users SET status=? WHERE username=? COLLATE NOCASE AND deleted=0`, status, username)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func (d *DB) BumpTokenVersion(username string) error {
	_, err := d.sql.Exec(`UPDATE users SET token_version=token_version+1 WHERE username=? COLLATE NOCASE AND deleted=0`, username)
	return err
}

func (d *DB) CountDevices(username string) (int, error) {
	var n int
	err := d.sql.QueryRow(`SELECT COUNT(*) FROM devices WHERE username=? COLLATE NOCASE AND revoked=0`, username).Scan(&n)
	return n, err
}

func (d *DB) FindDevice(username, deviceID string) (*Device, error) {
	var dv Device
	err := d.sql.QueryRow(`SELECT device_id,device_name,created_at,last_seen_at FROM devices
		WHERE username=? COLLATE NOCASE AND device_id=? AND revoked=0`, username, deviceID).
		Scan(&dv.DeviceID, &dv.DeviceName, &dv.CreatedAt, &dv.LastSeenAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &dv, nil
}

func (d *DB) AddDevice(username, deviceID, deviceName string) error {
	t := now()
	_, err := d.sql.Exec(`INSERT INTO devices(username,device_id,device_name,created_at,last_seen_at,revoked)
		VALUES(?,?,?,?,?,0)
		ON CONFLICT(username,device_id) DO UPDATE SET revoked=0, device_name=excluded.device_name, last_seen_at=excluded.last_seen_at`,
		username, deviceID, deviceName, t, t)
	return err
}

func (d *DB) TouchDevice(username, deviceID string) {
	_, _ = d.sql.Exec(`UPDATE devices SET last_seen_at=? WHERE username=? COLLATE NOCASE AND device_id=?`, now(), username, deviceID)
}

func (d *DB) RevokeDevice(username, deviceID string) error {
	res, err := d.sql.Exec(`UPDATE devices SET revoked=1 WHERE username=? COLLATE NOCASE AND device_id=? AND revoked=0`, username, deviceID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func (d *DB) ListDevices(username string) ([]Device, error) {
	rows, err := d.sql.Query(`SELECT device_id,device_name,created_at,last_seen_at FROM devices
		WHERE username=? COLLATE NOCASE AND revoked=0 ORDER BY created_at`, username)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Device
	for rows.Next() {
		var dv Device
		if err := rows.Scan(&dv.DeviceID, &dv.DeviceName, &dv.CreatedAt, &dv.LastSeenAt); err != nil {
			return nil, err
		}
		out = append(out, dv)
	}
	return out, rows.Err()
}

func (d *DB) LogSearch(username, keyword string) error {
	_, err := d.sql.Exec(`INSERT INTO search_logs(username,keyword,created_at) VALUES(?,?,?)`, username, keyword, now())
	return err
}