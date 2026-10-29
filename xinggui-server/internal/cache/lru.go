package cache

import (
	"container/list"
	"sync"
	"time"
)

type entry struct {
	key     string
	value   any
	expires time.Time
}

// LRU 是带 TTL 的进程内缓存，固定容量，线程安全。
type LRU struct {
	mu       sync.Mutex
	capacity int
	ll       *list.List
	items    map[string]*list.Element
}

func New(capacity int) *LRU {
	if capacity <= 0 {
		capacity = 512
	}
	return &LRU{capacity: capacity, ll: list.New(), items: make(map[string]*list.Element, capacity)}
}

func (c *LRU) Get(key string) (any, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	el, ok := c.items[key]
	if !ok {
		return nil, false
	}
	e := el.Value.(*entry)
	if time.Now().After(e.expires) {
		c.ll.Remove(el)
		delete(c.items, key)
		return nil, false
	}
	c.ll.MoveToFront(el)
	return e.value, true
}

func (c *LRU) Set(key string, value any, ttl time.Duration) {
	if ttl <= 0 {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	exp := time.Now().Add(ttl)
	if el, ok := c.items[key]; ok {
		e := el.Value.(*entry)
		e.value = value
		e.expires = exp
		c.ll.MoveToFront(el)
		return
	}
	el := c.ll.PushFront(&entry{key: key, value: value, expires: exp})
	c.items[key] = el
	for c.ll.Len() > c.capacity {
		back := c.ll.Back()
		if back == nil {
			break
		}
		c.ll.Remove(back)
		delete(c.items, back.Value.(*entry).key)
	}
}

func (c *LRU) Len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.ll.Len()
}