use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct Packet {
    pub r#type: String,
    pub id: String,
    pub timestamp: u64,
    pub payload: serde_json::Value,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct DeviceInfo {
    pub name: String,
    pub ip: String,
    pub port: u16,
    pub fingerprint: String,
    pub device_type: String,
    pub paired: bool,
    #[serde(default)]
    pub username: Option<String>,
    #[serde(default)]
    pub uuid: Option<String>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct UserProfile {
    pub uuid: String,
    pub username: String,
    pub device_name: String,
    #[serde(default)]
    pub onboarding_completed: bool,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct NotificationItem {
    pub id: String,
    pub app_name: String,
    pub title: String,
    pub body: String,
    pub timestamp: u64,
    pub is_read: bool,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct BugReportPayload {
    pub username: String,
    pub device_name: String,
    pub device_model: String,
    pub os: String,
    pub app_version: String,
    pub severity: String,
    pub description: String,
    pub uuid: String,
    pub platform: String,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct PrepareUploadRequest {
    pub files: Vec<FileMetadata>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct FileMetadata {
    pub name: String,
    pub size: u64,
    pub hash: String,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct PrepareUploadResponse {
    pub session_id: String,
    #[serde(default)]
    pub accepted_files: Vec<String>, // List of file names/hashes accepted
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_packet_serialization() {
        let packet = Packet {
            r#type: "device.status".to_string(),
            id: "test-uuid-1234".to_string(),
            timestamp: 1723980000,
            payload: serde_json::json!({
                "battery_level": 85,
                "is_charging": true,
                "signal_level": 4
            }),
        };

        let json_str = serde_json::to_string(&packet).expect("Failed to serialize Packet");
        assert!(json_str.contains("device.status"));
        assert!(json_str.contains("85"));

        let deserialized: Packet = serde_json::from_str(&json_str).expect("Failed to deserialize");
        assert_eq!(deserialized.r#type, "device.status");
        assert_eq!(deserialized.id, "test-uuid-1234");
        assert_eq!(deserialized.payload["battery_level"], 85);
    }

    #[test]
    fn test_device_info_structure() {
        let dev = DeviceInfo {
            name: "Pixel 8 Pro".to_string(),
            ip: "192.168.1.100".to_string(),
            port: 53318,
            fingerprint: "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890".to_string(),
            device_type: "android".to_string(),
            paired: true,
            username: Some("Babbi".to_string()),
            uuid: Some("uuid-1234".to_string()),
        };

        let val = serde_json::to_value(&dev).expect("Serialization failed");
        assert_eq!(val["name"], "Pixel 8 Pro");
        assert_eq!(val["paired"], true);
    }

    #[test]
    fn test_prepare_upload_negotiation() {
        let req = PrepareUploadRequest {
            files: vec![
                FileMetadata {
                    name: "photo.jpg".to_string(),
                    size: 2048576,
                    hash: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".to_string(),
                }
            ],
        };

        let serialized = serde_json::to_string(&req).expect("Failed serialize upload request");
        assert!(serialized.contains("photo.jpg"));
        assert!(serialized.contains("2048576"));
    }
}
