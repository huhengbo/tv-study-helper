# 参与贡献

欢迎提交 Issue 和 Pull Request。参与前请先阅读 [行为准则](CODE_OF_CONDUCT.md)；发现安全问题请按 [SECURITY.md](SECURITY.md) 私下报告。

## 先沟通再动手

- 修 bug、补测试、改文档可以直接提 PR。
- 新功能和较大的改动请先开 Issue 讨论，避免方向不一致白费功夫。
- 想分享题库？题库包的格式见 [README 的「题库包」](README.md#题库包)。

## 开发环境

需要 JDK 17 和 Android SDK（compileSdk 35）。目标设备是小米电视 4A（Android 6.0.1，API 23），用到的系统接口不能高于 API 23，或者要做版本判断——`lint` 会检查。

```sh
scripts/check.sh   # 全部检查，提交前必须通过
```

`scripts/check.sh` 依次运行版本号校验、单元测试（`testDebugUnitTest`）、`lintRelease`（不允许有错误）和 release 打包。CI（`.github/workflows/ci.yml`）跑同样的检查。

改动涉及电视上的行为（计时、锁定、语音键、录音、浮层）时，请在真机上试过再提交，并在 PR 里写明电视型号和系统版本。目前只在小米电视 4A 上验证过，其他型号的测试结果非常欢迎。

## 代码约定

- Kotlin 遵循官方代码风格（`kotlin.code.style=official`）；注释和界面文字用中文，说明「为什么」而不是复述代码。
- 电视内存小，不随手引入大依赖（HTTP 用自带的 `HttpURLConnection`，JSON 用 `org.json`）。
- 手机管理页是原生 HTML/CSS/JS（`tv/src/main/assets/web/`），不引入构建工具和框架。
- 修缺陷要附能复现该缺陷的测试；新功能要有对应测试，不要靠删除或放宽断言通过检查。
- 不提交密钥、语音服务的 API Key、`local.properties` 和 `secrets/`。

## 提交与 PR

- 提交信息使用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)，例如 `fix(quiz): 修复答错清零后重复出题`、`feat(web): 题库按年级筛选`。
- 一个 PR 只做一件事，说明动机、做法和验证方式；界面改动附截图。
- 用户可感知的变更（新功能、修复、行为调整）记在 [CHANGELOG.md](CHANGELOG.md) 的 `[Unreleased]` 下；发版流程见 [README 的「发布流程」](README.md#发布流程)。

## 许可

提交代码即表示你同意以 [Apache-2.0](LICENSE) 授权你的贡献；提交到内置题库（`tv/src/main/assets/packs/`）的题目以 [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/deed.zh-hans) 授权。
