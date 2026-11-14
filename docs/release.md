# Windows 发行包

发行包包含应用与 Java 21 运行时，面向 Windows x64。完整解压后运行 `start.cmd`，在 Chrome 或 Edge 打开 `http://127.0.0.1:8080/`。保留启动窗口，按 `Ctrl+C` 关闭应用。

默认数据保存在当前账号的 `%LOCALAPPDATA%/KnowPool/data`，与程序目录分离；同一数据目录只能由一个应用进程使用。可运行 `powershell -File start.ps1 -DataDirectory D:/KnowPoolData -Port 8081` 指定本机目录和端口。数据包括原文件、数据库及明文模型密钥，备份应按个人资料保管。

Qdrant 需独立部署并在应用设置中显式配置；发行包不携带个人资料、数据库、密钥或 Qdrant 数据。模型配置没有环境变量兜底，连接、检索及重建可能产生费用。普通验证使用替身，真实模型效果评测须单独授权。

升级前关闭旧进程并备份完整数据目录，将新包解压到独立程序目录，继续使用原数据目录。升级失败时保留原目录及自动升级前数据库备份；勿用旧程序写入较新数据库，不直接覆盖正在使用的文件或擅自删除失败残留。恢复数据和向量重建按应用维护流程操作。

## 装配

全部实现完成后先统一验证与构建，再从源码根目录运行：

```powershell
powershell -File scripts/release/package.ps1 -JdkDirectory D:/Tools/jdk-21
```

脚本只复制本次应用包、启动文件、使用说明和指定 Java 运行时，生成文件清单及压缩包 SHA-256；不会打包开发数据目录。输出默认在 `target/releases/`，每次使用独立目录，不覆盖旧包。需使用许可允许分发的 Java 21 开发工具，运行时法律声明随包保留。

装配前检查应用包同时具有正式前端和后端入口。完成装配后，将压缩包解压到新的独立目录，使用包内运行时分别执行两浏览器的同一业务链：

```powershell
python scripts/contract/run_live.py --release-directory D:/KnowPoolRelease --browser msedge
python scripts/contract/run_live.py --release-directory D:/KnowPoolRelease --browser chrome
```

验证启动前核对清单、全部文件摘要及清单外文件，拒绝缺件、篡改和链接路径；该核对只检查完整性，不代表发布者身份认证。两次运行各用独立临时数据目录，不读取个人模型配置，结束关闭自建进程。开发机需已安装对应浏览器及固定前端工具链；目标用户启动仍只需解压后的运行时。浏览器记录保留 `contract-browser-msedge.json`、`contract-browser-chrome.json` 与各自截图目录，公共报告指向最后一次运行；不能以存在旧报告代替本次成功。

完整验收先在最终集成快照运行根 `mvn verify`，再执行装配及两浏览器发行回归，生成同一实现和契约的不可变报告，前后端各自签认。中文评测与资源测量见[评测说明](evaluation.md)，模拟结果不能作为真实模型效果或推荐性能值。

全部票据实现已集成，装配脚本待完整门禁收敛后执行发行回归。打包成功不代表 Chrome/Edge、完整契约、升级恢复、真实模型效果及目标环境资源边界已经验收。
