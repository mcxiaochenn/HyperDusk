import 'package:flutter/material.dart';
import 'package:flutter_miuix/miuix.dart';
import 'package:hyperdusk/src/core/app_controller.dart';
import 'package:hyperdusk/src/ui/home_page.dart';

/// 应用根组件。
///
/// MaterialApp 只承担 Navigator / 路由过渡 / `showLicensePage` 这类不可控路径；
/// 页面主体全部由 flutter_miuix 渲染：`builder` 里套一层 [MiuixThemeController]，
/// 让 MaterialApp 的 Navigator 及其所有页面都拿得到 Miuix 主题。
class HyperDuskApp extends StatelessWidget {
  const HyperDuskApp({required this.controller, super.key});

  final AppController controller;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'HyperDusk',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: const Color(0xFF3482FF)),
      darkTheme: ThemeData(
        colorSchemeSeed: const Color(0xFF3482FF),
        brightness: Brightness.dark,
      ),
      themeMode: ThemeMode.system,
      builder: (context, child) => MiuixThemeController(
        colorSchemeMode: MiuixColorSchemeMode.system,
        // 透明 Material 只作为 Text 的默认样式祖先：MiuixScaffold 不是 Material
        // 页面，缺了它所有正文都会带上 Flutter 的黄色缺省下划线（MiuixTopAppBar
        // 内部自带 Material，所以只有页面主体的文字出问题）。视觉组件仍全部由
        // flutter_miuix 绘制，这里没有使用任何 Material 视觉组件。
        child: Material(
          type: MaterialType.transparency,
          child: child ?? const SizedBox.shrink(),
        ),
      ),
      home: HomePage(controller: controller),
    );
  }
}
