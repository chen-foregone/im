# 盒子IM 性能测试环境

用 Docker Compose 起两个容器，作为 box-im 群聊压测的被测环境。

## 提供什么

| 服务 | 镜像 | 宿主机端口 | 账号 |
|---|---|---|---|
| MySQL | `mysql:8.0` | **3306** | root / root |
| Redis | `redis:6.2` | **6380** | 无密码 |

> ⚠️ **Redis 是 6380，不是 6379。**
> 你本机 6379 上已经跑着另一个 Redis（带密码、有数据），压测必须独占 Redis，
> 否则测到的是"两台项目抢资源"的结果，不是 box-im 的天花板。

版本按 box-im README 的官方要求选（JDK 17 / MySQL 8.0 / Redis 6.2），
这样压出来的数据才能和官方那份压测报告（千人群 179 条/秒、万人群 14.2 条/秒）对比。

## 目录结构

```
docker/
├── docker-compose.yml
├── README.md
├── load-data.ps1          # 导入 SQL 的辅助脚本
└── redis/redis.conf       # Redis 压测参数
```

> MySQL 的压测参数写在 `docker-compose.yml` 的 `command:` 里，**不是**配置文件。
> 原因：Windows 上绑定挂载的 `.cnf` 在容器内权限是 0777，MySQL 会以
> `World-writable config file ... is ignored` 为由**静默跳过整个文件**。
> 这个坑很阴——不报错，只是你的调优全部不生效。

## 常用命令

> Docker 没进 PATH，若提示找不到 `docker`，先在当前窗口执行：
> `$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')`

```powershell
cd D:\Software\SourceCode\IM\docker

docker compose up -d            # 启动
docker compose ps               # 看状态（等 STATUS 变成 healthy）
docker compose logs -f mysql    # 看首次建表日志
docker compose stop             # 停（保留数据）
docker compose down             # 删除容器（保留数据卷）
```

## 导入测试数据

首次启动时 `box-im/db/im-platform.sql` 会自动建表（挂载到 docker-entrypoint-initdb.d）。
**但测试数据要手动导**，因为它有 34 MB：

```powershell
.\load-data.ps1 ..\box-im-simulator\db\汇总.sql
```

导完应该有：

| 表 | 行数 |
|---|---|
| `im_user` | 10,000 |
| `im_friend` | 999,000 |
| `im_group` | 1,120 |
| `im_group_member` | 130,000 |

## 想彻底重来（清库重新建表）

```powershell
docker compose down -v          # -v 会删掉数据卷，表和数据全没
docker compose up -d            # 重新走一遍 initdb，自动建表
```

## 这些参数是故意调过的（别照抄到生产）

两个配置文件里都做了"牺牲持久性换吞吐"的取舍，**目的是把磁盘 I/O 从压测结果里排除**：

| 位置 | 参数 | 原因 |
|---|---|---|
| `docker-compose.yml` | `innodb-flush-log-at-trx-commit=2` | 不每条提交都 fsync，否则**磁盘会掩盖 Redis 瓶颈** |
| `docker-compose.yml` | `skip-log-bin` | 关掉 binlog，省一次写 |
| `docker-compose.yml` | `innodb-buffer-pool-size=2G` | 默认仅 128M |
| `redis/redis.conf` | `appendonly no` + `save ""` | Redis 完全不落盘 |

**生产环境绝对不要这样配。**

## 顺带：验证 Redis 本身的吞吐

Redis 6.2 自带 benchmark。先用它量一条基线，能帮你区分
"Redis 本身不行"还是"box-im 用 Redis 的方式不行"：

```powershell
docker exec boxim-redis redis-benchmark -t get -n 1000000 -c 50 -q
```

参考：如果结果落在 **60~70 万次/秒**，就印证了
"box-im 群聊瓶颈 = 每条消息 4N 次 Redis key 查询" 这个推断
（N=群成员数，4 是终端类型数）。
