# 配置账号和双端同步

不配置云服务也能试用录题和复习。云服务不会由安装 APK 自动开通。以下步骤由题库主人在**自己的** Supabase 项目或兼容自托管实例上完成；先确认费用、所在地网络可达性和备份策略。

1. 选择一个专用项目。在 SQL Editor 运行仓库的 [迁移](../supabase/migrations/202611260001_page_reprise.sql)，或用 Supabase CLI 对应的迁移命令部署。该迁移注册账号邀请触发器，创建只追加的操作表和私有图片桶。不要把桶改为公开，也不要另设对所有用户开放的读写策略。
2. 在 Auth 中启用邮箱密码登录，关闭公开注册。把 **Invite user** 邮件模板改为展示一次性邀请码（模板变量 `{{ .Token }}`），确认项目的邮件服务能给受邀邮箱投递。用 Dashboard 的 **Invite user** 邀请自己、好友，每人使用自己的账号。正式邀请自动列入成员名单，普通自行注册不能读取学习数据。
3. 在平板和手机进入「设置 → 登录受邀账号」，输入同一项目 HTTPS 根地址及 **publishable key**（也支持旧的 anon key）。第一次输入邀请邮件的数字码并设置至少八位密码；以后只填邮箱和密码。请勿输入 service_role、secret key、数据库密码或网盘密码。
4. 两台设备登录同一账号。已存在的本机试用题目不会自动上传；如要迁移，先在有题的设备点击「把本机试用题目复制到此账号」。没有正式入本的草稿始终留在原设备。
5. 测试：平板录题 → 设置中显示已同步 → 手机打开或手动同步，核对相同科目和本别；然后在好友账号核对看不到该题。首次同步前不会把未知当作零待办。

若由管理员通过其他方式创建了已验证账号，管理员可以在 SQL Editor 执行下列语句手工加入成员名单；首先核对邮箱。不要向客户端暴露管理密钥。

```sql
insert into reprise_private.members(user_id)
select id from auth.users where email = 'replace-with-invited-email@example.invalid'
on conflict do nothing;
```

客户端先上传图片，再追加作答事件。云端用账号与事件标识保证幂等，设备通过同一套规则重建排期。冲突会在首页显示「核对双端记录」。登录会话用 Android Keystore 加密；退出后本地缓存仍保留，设备应有锁屏保护。撤销云端成员资格会停止云端访问，但不会远程擦除已缓存的题图。

官方参考：[邮箱模板](https://supabase.com/docs/guides/auth/auth-email-templates)、[数据库行级权限](https://supabase.com/docs/guides/database/postgres/row-level-security)、[私有文件访问](https://supabase.com/docs/guides/storage/security/access-control)。
