use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::PathBuf;
use rcgen::{CertificateParams, KeyPair, DistinguishedName, SanType, DnType};
use ring::digest;
use serde::{Serialize, Deserialize};

#[derive(Clone)]
pub struct Identity {
    pub cert_pem: String,
    pub key_pem: String,
    pub fingerprint: String,
}

pub fn get_or_create_identity(config_dir: PathBuf) -> Result<Identity, String> {
    let cert_path = config_dir.join("cert.pem");
    let key_path = config_dir.join("key.pem");

    if cert_path.exists() && key_path.exists() {
        let mut cert_pem = String::new();
        let mut key_pem = String::new();
        
        File::open(&cert_path)
            .map_err(|e| e.to_string())?
            .read_to_string(&mut cert_pem)
            .map_err(|e| e.to_string())?;
            
        File::open(&key_path)
            .map_err(|e| e.to_string())?
            .read_to_string(&mut key_pem)
            .map_err(|e| e.to_string())?;

        let fingerprint = compute_fingerprint_from_pem(&cert_pem)?;

        return Ok(Identity {
            cert_pem,
            key_pem,
            fingerprint,
        });
    }

    // Create config dir if not exists
    fs::create_dir_all(&config_dir).map_err(|e| e.to_string())?;

    // Generate new certificate using rcgen 0.13
    let mut params = CertificateParams::default();
    params.distinguished_name = DistinguishedName::new();
    params.distinguished_name.push(DnType::CommonName, "Janus Node");
    params.subject_alt_names = vec![
        SanType::DnsName(rcgen::Ia5String::try_from("localhost").unwrap()),
        SanType::DnsName(rcgen::Ia5String::try_from("janus.local").unwrap()),
    ];

    let key_pair = KeyPair::generate().map_err(|e| format!("Failed to generate key pair: {}", e))?;
    let cert = params.self_signed(&key_pair)
        .map_err(|e| format!("Failed to generate self-signed cert: {}", e))?;

    let cert_pem = cert.pem();
    let key_pem = key_pair.serialize_pem();

    // Save to files
    let mut cert_file = File::create(&cert_path).map_err(|e| e.to_string())?;
    cert_file.write_all(cert_pem.as_bytes()).map_err(|e| e.to_string())?;

    let mut key_file = File::create(&key_path).map_err(|e| e.to_string())?;
    key_file.write_all(key_pem.as_bytes()).map_err(|e| e.to_string())?;

    let fingerprint = compute_fingerprint_from_pem(&cert_pem)?;

    println!("Generated new self-signed identity. Fingerprint: {}", fingerprint);

    Ok(Identity {
        cert_pem,
        key_pem,
        fingerprint,
    })
}

fn compute_fingerprint_from_pem(cert_pem: &str) -> Result<String, String> {
    let parsed_pem = pem::parse(cert_pem)
        .map_err(|e| format!("Failed to parse certificate PEM: {}", e))?;
    let der = parsed_pem.contents();

    // Compute SHA-256 fingerprint
    let hash = digest::digest(&digest::SHA256, &der);
    let hex_fingerprint = hash.as_ref()
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect::<Vec<String>>()
        .join("");

    Ok(hex_fingerprint)
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct PairedDeviceStore {
    pub fingerprint: String,
    pub name: String,
    pub added_at: u64,
}

pub fn load_paired_devices(config_dir: PathBuf) -> Result<Vec<PairedDeviceStore>, String> {
    let path = config_dir.join("paired_devices.json");
    if !path.exists() {
        return Ok(Vec::new());
    }

    let file_content = fs::read_to_string(path).map_err(|e| e.to_string())?;
    let devices: Vec<PairedDeviceStore> = serde_json::from_str(&file_content).map_err(|e| e.to_string())?;
    Ok(devices)
}

pub fn save_paired_device(config_dir: PathBuf, device: PairedDeviceStore) -> Result<(), String> {
    let mut devices = load_paired_devices(config_dir.clone())?;
    
    // Check if already paired, update or insert
    if let Some(pos) = devices.iter().position(|d| d.fingerprint == device.fingerprint) {
        devices[pos] = device;
    } else {
        devices.push(device);
    }

    let path = config_dir.join("paired_devices.json");
    let file_content = serde_json::to_string_pretty(&devices).map_err(|e| e.to_string())?;
    fs::write(path, file_content).map_err(|e| e.to_string())?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_paired_device_store_roundtrip() {
        let unique_id = uuid::Uuid::new_v4().to_string();
        let path = std::env::temp_dir().join(format!("janus_test_{}", unique_id));
        let _ = fs::create_dir_all(&path);

        let device = PairedDeviceStore {
            fingerprint: "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff".to_string(),
            name: "Galaxy S24".to_string(),
            added_at: 1723985000,
        };

        save_paired_device(path.clone(), device.clone()).expect("Failed to save device");
        let loaded = load_paired_devices(path.clone()).expect("Failed to load devices");

        assert_eq!(loaded.len(), 1);
        assert_eq!(loaded[0].name, "Galaxy S24");
        assert_eq!(loaded[0].fingerprint, device.fingerprint);

        // Test updating existing device
        let updated_device = PairedDeviceStore {
            fingerprint: device.fingerprint.clone(),
            name: "Galaxy S24 Ultra".to_string(),
            added_at: 1723986000,
        };
        save_paired_device(path.clone(), updated_device).expect("Failed update");
        let reloaded = load_paired_devices(path.clone()).expect("Failed reload");
        assert_eq!(reloaded.len(), 1);
        assert_eq!(reloaded[0].name, "Galaxy S24 Ultra");
        let _ = fs::remove_dir_all(&path);
    }
}
