# Android App 重构清单

> 待完成的重构项

---

## 待完成（1 项）

### A10. 无接口抽象，不可测试

- **问题**: BleManager、HttpRepository、WifiScanner 都是具体类注入，无接口
- **方案**: 为 Repository 层定义接口，支持 mock 测试
- **优先级**: P3
- **预估工作量**: 4h

---

## 已完成

| 优先级 | 问题 | 状态 |
|--------|------|------|
| P0 | BleManager 拆分 | ✅ |
| P0 | OtaViewModel 提取 OtaRepository | ✅ |
| P0 | HttpRepository 错误处理 | ✅ |
| P2 | 通知逻辑移出 ViewModel | ✅ |
| P2 | 导航路由类型安全 | ✅ |
