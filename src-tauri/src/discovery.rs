use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Mutex;
use mdns_sd::{ServiceDaemon, ServiceInfo, ServiceEvent};
use tauri::{AppHandle, Emitter};
use crate::protocol::DeviceInfo;

use std::sync::OnceLock;

static DAEMON: OnceLock<ServiceDaemon> = OnceLock::new();
static ACTIVE_SERVICE: Mutex<Option<ServiceInfo>> = Mutex::new(None);
static BROWSER_THREAD: Mutex<Option<tokio::task::JoinHandle<()>>> = Mutex::new(None);

fn get_daemon() -> &'static ServiceDaemon {
    DAEMON.get_or_init(|| ServiceDaemon::new().expect("Failed to create mdns daemon"))
}

pub fn get_best_local_ip() -> String {
    // Try the default local IP first
    if let Ok(ip) = local_ip_address::local_ip() {
        return ip.to_string();
    }

    // Fallback to enumerating interfaces (for offline network environments)
    if let Ok(interfaces) = local_ip_address::list_afinet_netifas() {
        // Try en, wlan, or eth interfaces first (IPv4)
        for (name, ip) in &interfaces {
            if let IpAddr::V4(ipv4) = ip {
                if !ipv4.is_loopback() && !ipv4.is_link_local() {
                    let name_lower = name.to_lowercase();
                    if name_lower.starts_with("en") || name_lower.starts_with("wlan") || name_lower.starts_with("eth") {
                        return ipv4.to_string();
                    }
                }
            }
        }

        // Try any non-loopback non-link-local IPv4 interface next
        for (_name, ip) in &interfaces {
            if let IpAddr::V4(ipv4) = ip {
                if !ipv4.is_loopback() && !ipv4.is_link_local() {
                    return ipv4.to_string();
                }
            }
        }
    }

    "127.0.0.1".to_string()
}

pub fn get_all_local_ips() -> Vec<String> {
    let mut ips = Vec::new();
    if let Ok(interfaces) = local_ip_address::list_afinet_netifas() {
        for (_name, ip) in &interfaces {
            if let IpAddr::V4(ipv4) = ip {
                if !ipv4.is_loopback() && !ipv4.is_link_local() {
                    ips.push(ipv4.to_string());
                }
            }
        }
    }
    ips.sort();
    ips.dedup();
    if ips.is_empty() {
        ips.push("127.0.0.1".to_string());
    }
    ips
}


pub fn start_advertising(name: &str, port: u16, fingerprint: &str) -> Result<(), String> {
    let mut active = ACTIVE_SERVICE.lock().unwrap();
    if active.is_some() {
        return Ok(());
    }

    let ip_str = get_best_local_ip();

    let service_type = "_janus._tcp.local.";
    let instance_name = format!("{}.{}", name, fingerprint.chars().take(8).collect::<String>());
    let host_name = format!("{}.local.", instance_name.replace(" ", "-"));

    let mut properties = HashMap::new();
    properties.insert("dn".to_string(), name.to_string());
    properties.insert("fn".to_string(), fingerprint.to_string());
    properties.insert("dt".to_string(), "macos".to_string());
    properties.insert("pv".to_string(), "1".to_string());
    properties.insert("ip".to_string(), ip_str.clone());

    let service_info = ServiceInfo::new(
        service_type,
        &instance_name,
        &host_name,
        &ip_str,
        port,
        Some(properties),
    ).map_err(|e| format!("Failed to create service info: {}", e))?;

    get_daemon().register(service_info.clone())
        .map_err(|e| format!("Failed to register mdns service: {}", e))?;

    *active = Some(service_info);
    println!("Advertising Janus device: {} on {}:{}", name, ip_str, port);
    Ok(())
}

pub fn stop_advertising() -> Result<(), String> {
    let mut active = ACTIVE_SERVICE.lock().unwrap();
    if let Some(service_info) = active.take() {
        get_daemon().unregister(&service_info.get_type())
            .map_err(|e| format!("Failed to unregister: {}", e))?;
        println!("Stopped advertising Janus device");
    }
    Ok(())
}

pub fn start_browsing(app_handle: AppHandle) -> Result<(), String> {
    let mut browser = BROWSER_THREAD.lock().unwrap();
    if browser.is_some() {
        return Ok(());
    }

    let service_type = "_janus._tcp.local.";
    let receiver = get_daemon().browse(service_type)
        .map_err(|e| format!("Failed to browse mdns services: {}", e))?;

    let handle = tokio::spawn(async move {
        while let Ok(event) = receiver.recv_async().await {
            match event {
                ServiceEvent::ServiceResolved(info) => {
                    let name = info.get_property_val_str("dn").unwrap_or_else(|| info.get_fullname()).to_string();
                    let fingerprint = info.get_property_val_str("fn").unwrap_or_default().to_string();
                    let device_type = info.get_property_val_str("dt").unwrap_or("unknown").to_string();
                    let txt_ip = info.get_property_val_str("ip").map(|s| s.to_string());
                    
                    let ips = info.get_addresses();
                    let resolved_ip = txt_ip.or_else(|| {
                        ips.iter()
                            .find(|ip| ip.is_ipv4())
                            .or_else(|| ips.iter().next())
                            .map(|ip| ip.to_string())
                    });
                    
                    if let Some(ip) = resolved_ip {
                        let device = DeviceInfo {
                            name: name.clone(),
                            ip: ip.clone(),
                            port: info.get_port(),
                            fingerprint: fingerprint.clone(),
                            device_type: device_type.clone(),
                            paired: false, // will check pairing status separately
                        };
                        
                        println!("Discovered device: {:?} at {}:{}", name, ip, info.get_port());
                        let _ = app_handle.emit("device-discovered", device);
                    }
                }
                ServiceEvent::ServiceRemoved(_service_type, fullname) => {
                    println!("Device removed: {}", fullname);
                    let _ = app_handle.emit("device-removed", fullname);
                }
                _ => {}
            }
        }
    });

    *browser = Some(handle);
    println!("Started scanning for Janus devices...");
    Ok(())
}

pub fn stop_browsing() {
    let mut browser = BROWSER_THREAD.lock().unwrap();
    if let Some(handle) = browser.take() {
        handle.abort();
        println!("Stopped scanning for Janus devices");
    }
}
