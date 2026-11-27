#!/usr/bin/env python3
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SDK = Path("/mnt/new_volume/asdk-smoke")
ADB = str(SDK / "platform-tools/adb")
EMULATOR = str(SDK / "emulator/emulator")
AVD_HOME = "/mnt/new_volume/gm-avds"

DEVICES = [
    ("phone", "gm-galaxy", True),
    ("compact", "gm-compact", False),
    ("tablet", "gm-tablet", False),
]

def run(cmd, timeout=60, check=False):
    return subprocess.run(cmd, text=True, capture_output=True, timeout=timeout, check=check)

def kill_all_emulators():
    subprocess.run(["pkill", "-9", "-f", "qemu-system"], check=False)
    subprocess.run(["pkill", "-9", "-f", "emulator"], check=False)
    subprocess.run([ADB, "kill-server"], check=False)
    time.sleep(2)
    subprocess.run([ADB, "start-server"], check=False)
    time.sleep(1)

def boot_avd(avd_name):
    print(f"\n==========================================")
    print(f"Launching AVD: {avd_name}")
    print(f"==========================================")
    kill_all_emulators()
    env = dict(os.environ)
    env["ANDROID_HOME"] = str(SDK)
    env["ANDROID_SDK_ROOT"] = str(SDK)
    env["ANDROID_AVD_HOME"] = AVD_HOME
    
    proc = subprocess.Popen(
        [EMULATOR, "-avd", avd_name, "-no-window", "-no-audio", "-no-boot-anim", "-gpu", "swiftshader_indirect", "-no-snapshot"],
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    
    # Wait for device
    print("Waiting for device connection...")
    subprocess.run([ADB, "wait-for-device"], timeout=60)
    for _ in range(60):
        res = run([ADB, "shell", "getprop", "sys.boot_completed"], timeout=5)
        if res.stdout.strip() == "1":
            print(f"AVD {avd_name} boot complete!")
            break
        time.sleep(2)
    else:
        print(f"Warning: boot_completed check timed out for {avd_name}")
    
    # Settle down
    time.sleep(4)
    return proc

def main():
    for device, avd_name, do_interactions in DEVICES:
        emu_proc = boot_avd(avd_name)
        try:
            cmd = [
                sys.executable,
                str(ROOT / "scripts/marketing-capture.py"),
                "--device", device,
                "--skip-existing",
            ]
            if do_interactions:
                cmd.append("--interactions")
            print(f"Running capture for {device}...")
            res = subprocess.run(cmd)
            print(f"Capture for {device} finished with exit code {res.returncode}")
        finally:
            print(f"Shutting down AVD {avd_name}...")
            kill_all_emulators()
            emu_proc.poll()
    
    print("\nAll captures complete! Finalizing deliverables...")
    subprocess.run([sys.executable, str(ROOT / "scripts/marketing-capture.py"), "--finalize"], check=True)
    print("All deliverables generated!")

if __name__ == "__main__":
    main()
