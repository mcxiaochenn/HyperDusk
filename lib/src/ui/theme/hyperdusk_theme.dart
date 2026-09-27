import 'package:flutter/material.dart';

abstract final class HyperDuskTheme {
  static const _accent = Color(0xFF3482FF);

  static ThemeData get light => _build(
    brightness: Brightness.light,
    background: const Color(0xFFF5F5F7),
    surface: Colors.white,
  );

  static ThemeData get dark => _build(
    brightness: Brightness.dark,
    background: const Color(0xFF000000),
    surface: const Color(0xFF1C1C1E),
  );

  static ThemeData _build({
    required Brightness brightness,
    required Color background,
    required Color surface,
  }) {
    final scheme = ColorScheme.fromSeed(
      seedColor: _accent,
      brightness: brightness,
      surface: surface,
    );
    return ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: scheme,
      scaffoldBackgroundColor: background,
      splashFactory: InkSparkle.splashFactory,
      textTheme: const TextTheme(
        displaySmall: TextStyle(fontSize: 34, fontWeight: FontWeight.w700),
        headlineSmall: TextStyle(fontSize: 22, fontWeight: FontWeight.w700),
        titleMedium: TextStyle(fontSize: 17, fontWeight: FontWeight.w600),
        bodyLarge: TextStyle(fontSize: 16, height: 1.4),
        bodyMedium: TextStyle(fontSize: 14, height: 1.4),
      ),
      pageTransitionsTheme: const PageTransitionsTheme(
        builders: {
          TargetPlatform.android: PredictiveBackPageTransitionsBuilder(),
        },
      ),
    );
  }
}
