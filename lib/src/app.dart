import 'package:flutter/material.dart';
import 'package:hyperdusk/src/core/app_controller.dart';
import 'package:hyperdusk/src/ui/home_page.dart';
import 'package:hyperdusk/src/ui/theme/hyperdusk_theme.dart';

class HyperDuskApp extends StatelessWidget {
  const HyperDuskApp({required this.controller, super.key});

  final AppController controller;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'HyperDusk',
      debugShowCheckedModeBanner: false,
      theme: HyperDuskTheme.light,
      darkTheme: HyperDuskTheme.dark,
      themeMode: ThemeMode.system,
      home: HomePage(controller: controller),
    );
  }
}
