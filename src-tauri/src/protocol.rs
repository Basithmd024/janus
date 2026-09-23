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
            fingerprint: "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
                .to_string(),
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
            files: vec![FileMetadata {
                name: "photo.jpg".to_string(),
                size: 2048576,
                hash: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                    .to_string(),
            }],
        };

        let serialized = serde_json::to_string(&req).expect("Failed serialize upload request");
        assert!(serialized.contains("photo.jpg"));
        assert!(serialized.contains("2048576"));
    }
}

// ══════════════════════════════════════════════════════════════════
// Secure Media & File Access Protocol Types
// ══════════════════════════════════════════════════════════════════

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct MediaItemMetadata {
    #[serde(rename = "mediaId")]
    pub media_id: String,
    pub name: String,
    #[serde(rename = "mimeType")]
    pub mime_type: String,
    pub size: u64,
    #[serde(rename = "dateModified")]
    pub date_modified: u64,
    #[serde(default)]
    pub width: Option<u32>,
    #[serde(default)]
    pub height: Option<u32>,
    #[serde(rename = "durationMs", default)]
    pub duration_ms: Option<u64>,
    pub category: String, // "photo", "screenshot", "video", "audio", "download"
    #[serde(default)]
    pub thumbnail: Option<String>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct GetFileRangeRequest {
    pub r#type: String, // "GET_FILE_RANGE"
    #[serde(rename = "requestId")]
    pub request_id: String,
    #[serde(rename = "mediaId")]
    pub media_id: String,
    pub offset: u64,
    pub length: u64,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct FileInfoResponse {
    pub r#type: String, // "FILE_INFO"
    #[serde(rename = "requestId")]
    pub request_id: String,
    #[serde(rename = "mediaId")]
    pub media_id: String,
    pub name: String,
    #[serde(rename = "mimeType")]
    pub mime_type: String,
    pub size: u64,
    pub offset: u64,
    pub length: u64,
    #[serde(rename = "totalSize")]
    pub total_size: u64,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct FileChunkPayload {
    pub r#type: String, // "FILE_CHUNK"
    #[serde(rename = "requestId")]
    pub request_id: String,
    #[serde(rename = "mediaId")]
    pub media_id: String,
    pub offset: u64,
    #[serde(rename = "chunkIndex")]
    pub chunk_index: u32,
    #[serde(rename = "totalChunks")]
    pub total_chunks: u32,
    pub data: String, // Base64 chunk
    #[serde(rename = "dataLength")]
    pub data_length: usize,
    #[serde(rename = "isLastChunk")]
    pub is_last_chunk: bool,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct FileCompletePayload {
    pub r#type: String, // "FILE_COMPLETE"
    #[serde(rename = "requestId")]
    pub request_id: String,
    #[serde(rename = "mediaId")]
    pub media_id: String,
    #[serde(rename = "totalBytesTransferred")]
    pub total_bytes_transferred: u64,
    #[serde(default)]
    pub sha256: Option<String>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct CancelTransferRequest {
    pub r#type: String, // "CANCEL_TRANSFER"
    #[serde(rename = "requestId")]
    pub request_id: String,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct SyncChangesRequest {
    pub r#type: String, // "SYNC_CHANGES"
    pub since: u64,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct MediaChangeEvent {
    pub r#type: String, // "MEDIA_CHANGE_EVENT"
    pub action: String, // "added", "updated", "deleted"
    #[serde(rename = "mediaId")]
    pub media_id: String,
    #[serde(default)]
    pub item: Option<MediaItemMetadata>,
}

/// Sanitizes display names received from Android to prevent path traversal and filesystem exploits on macOS.
pub fn sanitize_filename(input: &str) -> String {
    let mut clean = input
        .replace('/', "_")
        .replace('\\', "_")
        .replace(':', "_")
        .replace('\0', "");

    // Remove path traversal indicators
    while clean.contains("..") {
        clean = clean.replace("..", "_");
    }

    // Filter control chars and invisible characters
    clean = clean
        .chars()
        .filter(|c| !c.is_control())
        .collect::<String>()
        .trim_matches(|c: char| c == '.' || c.is_whitespace())
        .to_string();

    let has_alphanumeric = clean.chars().any(|c| c.is_alphanumeric());
    if clean.is_empty() || !has_alphanumeric {
        "file_download".to_string()
    } else if clean.len() > 255 {
        clean[..255].to_string()
    } else {
        clean
    }
}

/// Validates byte range parameters for GET_FILE_RANGE requests.
pub fn validate_file_range(offset: u64, length: u64, total_size: u64) -> Result<(), &'static str> {
    const MAX_RANGE_SIZE: u64 = 10 * 1024 * 1024; // 10 MB maximum chunk per single range request

    if total_size == 0 {
        return Err("File is empty (0 bytes)");
    }
    if offset >= total_size {
        return Err("Offset out of range");
    }
    if length == 0 {
        return Err("Requested length must be greater than zero");
    }
    if length > MAX_RANGE_SIZE {
        return Err("Requested length exceeds maximum 10MB limit");
    }
    if offset + length > total_size {
        return Err("Range exceeds total file size");
    }
    Ok(())
}

#[cfg(test)]
mod media_protocol_tests {
    use super::*;

    #[test]
    fn test_sanitize_filename_path_traversal() {
        assert_eq!(sanitize_filename("../../etc/passwd"), "____etc_passwd");
        assert_eq!(
            sanitize_filename("..\\..\\Windows\\System32"),
            "____Windows_System32"
        );
        assert_eq!(
            sanitize_filename("/var/root/secret.txt"),
            "_var_root_secret.txt"
        );
        assert_eq!(sanitize_filename("photo:name.jpg"), "photo_name.jpg");
        assert_eq!(sanitize_filename("..."), "file_download");
        assert_eq!(sanitize_filename(""), "file_download");
        assert_eq!(
            sanitize_filename("  clean_photo_123.jpg  "),
            "clean_photo_123.jpg"
        );
        assert_eq!(sanitize_filename("evil\0payload.png"), "evilpayload.png");
    }

    #[test]
    fn test_validate_file_range() {
        let total = 5_000_000u64; // 5 MB
        assert!(validate_file_range(0, 1_048_576, total).is_ok());
        assert!(validate_file_range(1_048_576, 1_048_576, total).is_ok());
        assert_eq!(
            validate_file_range(5_000_000, 1024, total),
            Err("Offset out of range")
        );
        assert_eq!(
            validate_file_range(0, 0, total),
            Err("Requested length must be greater than zero")
        );
        assert_eq!(
            validate_file_range(0, 20_000_000, total),
            Err("Requested length exceeds maximum 10MB limit")
        );
        assert_eq!(
            validate_file_range(4_500_000, 1_000_000, total),
            Err("Range exceeds total file size")
        );
        assert_eq!(
            validate_file_range(0, 100, 0),
            Err("File is empty (0 bytes)")
        );
    }

    #[test]
    fn test_media_item_metadata_serialization() {
        let item = MediaItemMetadata {
            media_id: "10042".to_string(),
            name: "vacation.mp4".to_string(),
            mime_type: "video/mp4".to_string(),
            size: 45_000_000,
            date_modified: 1727101234,
            width: Some(1920),
            height: Some(1080),
            duration_ms: Some(35400),
            category: "video".to_string(),
            thumbnail: None,
        };

        let json = serde_json::to_string(&item).unwrap();
        assert!(json.contains("\"mediaId\":\"10042\""));
        assert!(json.contains("\"mimeType\":\"video/mp4\""));
        assert!(json.contains("\"durationMs\":35400"));

        let roundtrip: MediaItemMetadata = serde_json::from_str(&json).unwrap();
        assert_eq!(roundtrip.name, "vacation.mp4");
        assert_eq!(roundtrip.width, Some(1920));
    }

    #[test]
    fn test_file_info_response_serialization() {
        let resp = FileInfoResponse {
            r#type: "FILE_INFO".to_string(),
            request_id: "req-987".to_string(),
            media_id: "55".to_string(),
            name: "test.pdf".to_string(),
            mime_type: "application/pdf".to_string(),
            size: 1048576,
            offset: 0,
            length: 1048576,
            total_size: 4194304,
        };

        let json = serde_json::to_string(&resp).unwrap();
        assert!(json.contains("\"requestId\":\"req-987\""));
        assert!(json.contains("\"totalSize\":4194304"));
    }
}
