/// 应用版本与项目链接常量。
///
/// Flutter 侧没有引入 `package_info_plus`（规格只允许 `flutter_miuix` 一个新依赖），
/// 运行时读不到 pubspec 里的 version，所以这里保留一份常量。
/// 修改版本号时必须同步 `pubspec.yaml` 的 `version`，两处保持一致。
const String kAppVersion = '0.0.1';

/// 项目主页，关于页的「项目主页」入口使用。
const String kProjectHomeUrl = 'https://github.com/mcxiaochenn/HyperDusk';

/// 问题反馈入口，指向项目 Issues。
const String kProjectIssuesUrl = '$kProjectHomeUrl/issues';
