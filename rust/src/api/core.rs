const DATA_SCHEMA_VERSION: u32 = 1;
const APP_VERSION: &str = "0.1.0";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CoreStatus {
    pub ready: bool,
    pub core_version: String,
    pub data_schema_version: u32,
    pub error: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AboutInfo {
    pub app_name: String,
    pub app_version: String,
    pub package_name: String,
    pub architecture: String,
    pub core_version: String,
    pub license: String,
}

#[flutter_rust_bridge::frb(sync)]
pub fn initialize_core() -> CoreStatus {
    CoreStatus {
        ready: true,
        core_version: env!("CARGO_PKG_VERSION").to_owned(),
        data_schema_version: DATA_SCHEMA_VERSION,
        error: None,
    }
}

#[flutter_rust_bridge::frb(sync)]
pub fn get_about_info() -> AboutInfo {
    AboutInfo {
        app_name: "HyperDusk".to_owned(),
        app_version: APP_VERSION.to_owned(),
        package_name: "com.mcxiaochen.hyperdusk".to_owned(),
        architecture: "Flutter UI · Rust core · libxposed API 102".to_owned(),
        core_version: env!("CARGO_PKG_VERSION").to_owned(),
        license: "MIT".to_owned(),
    }
}

#[flutter_rust_bridge::frb(init)]
pub fn init_app() {
    flutter_rust_bridge::setup_default_user_utils();
}

#[cfg(test)]
mod tests {
    use super::{DATA_SCHEMA_VERSION, get_about_info, initialize_core};

    #[test]
    fn initializes_with_a_stable_schema() {
        let status = initialize_core();

        assert!(status.ready);
        assert_eq!(status.data_schema_version, DATA_SCHEMA_VERSION);
        assert!(status.error.is_none());
        assert_eq!(status.core_version, env!("CARGO_PKG_VERSION"));
    }

    #[test]
    fn returns_non_sensitive_about_information() {
        let about = get_about_info();

        assert_eq!(about.app_name, "HyperDusk");
        assert_eq!(about.app_version, "0.1.0");
        assert_eq!(about.package_name, "com.mcxiaochen.hyperdusk");
        assert_eq!(about.license, "MIT");
        assert!(!about.architecture.is_empty());
    }
}
