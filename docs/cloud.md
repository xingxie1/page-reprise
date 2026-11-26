# 配置账号和双端同步

不配置云服务也能试用录题和复习。云服务不会由安装 APK 自动开通。以下步骤由题库主人在**自己的** Supabase 项目或兼容自托管实例上完成；先确认费用、所在地网络可达性和备份策略。

1. 选择一个专用项目，在 SQL Editor 按文件名顺序执行[数据库迁移](../supabase/migrations)，或使用 Supabase CLI 部署。已运行 0.1.0 的项目只需追加第二个迁移。所有设备同时升级到 0.2.0，再按下文设置定时清理。
2. 在 Auth 中启用邮箱密码提供方，关闭**全局**公开注册，不要关闭邮箱提供方本身。本地配置对应 `auth.enable_signup = false`、`auth.email.enable_signup = true`。把 **Invite user** 邮件模板改为展示一次性邀请码（模板变量 `{{ .Token }}`），确认项目的邮件服务能给受邀邮箱投递。用 Dashboard 的 **Invite user** 邀请自己、好友，每人使用自己的账号。正式邀请自动列入成员名单，普通自行注册不能读取学习数据。
3. 在平板和手机进入「设置 → 登录受邀账号」，输入同一项目 HTTPS 根地址及 **publishable key**（也支持旧的 anon key）。第一次输入邀请邮件的数字码并设置至少八位密码；以后只填邮箱和密码。请勿输入 service_role、secret key、数据库密码或网盘密码。
4. 两台设备登录同一账号。已存在的本机试用题目不会自动上传；如要迁移，先在有题的设备点击「把本机试用题目复制到此账号」。没有正式入本的草稿始终留在原设备。
5. 测试：平板录题 → 设置中显示已同步 → 手机打开或手动同步，核对相同科目和本别；然后在好友账号核对看不到该题。首次同步前不会把未知当作零待办。

若由管理员通过其他方式创建了已验证账号，管理员可以在 SQL Editor 执行下列语句手工加入成员名单；首先核对邮箱。不要向客户端暴露管理密钥。

```sql
insert into reprise_private.members(user_id)
select id from auth.users where email = 'replace-with-invited-email@example.invalid'
on conflict do nothing;
```

客户端先登记题目对图片的引用，再上传图片。云端按账号与操作标识去重，设备从同一份记录重建排期。冲突可在首页「核对双端记录」处理。登录会话由 Android Keystore 加密。退出账号后，本地缓存按账号隔离。

## 启用回收站的云端清理

1. 管理员在项目的 **Vault** 中创建两条机密。将 `reprise_project_url` 设为本项目 HTTPS 根地址，将 `reprise_storage_admin` 设为本项目服务端的 **service_role JWT**。这两项仅供服务端清理作业使用。平板和手机继续填 publishable 或 anon key。
2. 在 SQL Editor 执行[定时清理脚本](../supabase/setup-trash-cleanup.sql)。进入 Cron 页面，核对 `reprise-trash-expiry` 和 `reprise-trash-images` 两项任务。它们每分钟检查一次，应用全部关闭时也能执行。图片通过 Storage API 删除，失败的清理会重试。其他题仍引用的图片会保留。[Supabase 存储删除说明](https://supabase.com/docs/guides/storage/management/delete-objects)
3. 每道题从删除时刻起有 168 小时可以恢复。云题库以已同步的操作为准，另一台设备上的离线恢复应在期限内联网同步。服务端清理后仅保留防止旧备份回流的题目标识与清除时间。离线设备在下一次同步时清理本地副本。手动导出到夸克等处的历史 ZIP，由文件持有人管理。

官方参考：[邮箱模板](https://supabase.com/docs/guides/auth/auth-email-templates)、[数据库行级权限](https://supabase.com/docs/guides/database/postgres/row-level-security)、[私有文件访问](https://supabase.com/docs/guides/storage/security/access-control)。
