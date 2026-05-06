param(
    [int]$DurationSeconds = 25,
    [switch]$FailIfNoEvents
)

$ErrorActionPreference = 'Stop'

$runningOnWindows = $env:OS -eq 'Windows_NT'
if (-not $runningOnWindows) {
    throw 'This probe only runs on Windows.'
}

if ([Threading.Thread]::CurrentThread.GetApartmentState() -ne [Threading.ApartmentState]::STA) {
    $arguments = @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-STA',
        '-File', $PSCommandPath,
        '-DurationSeconds', $DurationSeconds
    )
    if ($FailIfNoEvents) {
        $arguments += '-FailIfNoEvents'
    }

    $process = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -Wait -PassThru -NoNewWindow
    exit $process.ExitCode
}

Add-Type -AssemblyName System.Windows.Forms

$typeDefinition = @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Windows.Forms;

public sealed class MediaHotkeyEvent {
    public string Label { get; set; }
    public uint VirtualKey { get; set; }
    public DateTime TimestampLocal { get; set; }
}

public sealed class MediaKeyProbeForm : Form {
    private const int WM_HOTKEY = 0x0312;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    private readonly Dictionary<int, Tuple<uint, string>> hotkeys = new Dictionary<int, Tuple<uint, string>>();
    private readonly List<string> registeredHotkeys = new List<string>();
    private readonly List<MediaHotkeyEvent> capturedEvents = new List<MediaHotkeyEvent>();

    public List<string> RegisteredHotkeys { get { return registeredHotkeys; } }
    public List<MediaHotkeyEvent> CapturedEvents { get { return capturedEvents; } }

    public MediaKeyProbeForm() {
        ShowInTaskbar = false;
        WindowState = FormWindowState.Minimized;
        FormBorderStyle = FormBorderStyle.FixedToolWindow;
        Opacity = 0;
    }

    protected override void OnHandleCreated(EventArgs e) {
        base.OnHandleCreated(e);
        Register(1, 0xB3, "MEDIA_PLAY_PAUSE");
        Register(2, 0xB0, "MEDIA_NEXT_TRACK");
        Register(3, 0xB1, "MEDIA_PREVIOUS_TRACK");
        Register(4, 0xB2, "MEDIA_STOP");
        Register(5, 0xAE, "VOLUME_DOWN");
        Register(6, 0xAF, "VOLUME_UP");
        Register(7, 0xAD, "VOLUME_MUTE");
    }

    protected override void Dispose(bool disposing) {
        if (IsHandleCreated) {
            foreach (var id in hotkeys.Keys) {
                UnregisterHotKey(Handle, id);
            }
        }

        base.Dispose(disposing);
    }

    protected override void WndProc(ref Message m) {
        if (m.Msg == WM_HOTKEY) {
            int id = m.WParam.ToInt32();
            Tuple<uint, string> hotkey;
            if (hotkeys.TryGetValue(id, out hotkey)) {
                var captured = new MediaHotkeyEvent {
                    Label = hotkey.Item2,
                    VirtualKey = hotkey.Item1,
                    TimestampLocal = DateTime.Now,
                };
                CapturedEvents.Add(captured);
                Console.WriteLine("{0:HH:mm:ss.fff}\t{1}\tVK=0x{2:X}", captured.TimestampLocal, captured.Label, captured.VirtualKey);
            }
        }

        base.WndProc(ref m);
    }

    private void Register(int id, uint virtualKey, string label) {
        bool registered = RegisterHotKey(Handle, id, 0, virtualKey);
        RegisteredHotkeys.Add(registered ? label : label + " (registration failed)");
        if (registered) {
            hotkeys[id] = Tuple.Create(virtualKey, label);
        }
    }
}
"@

Add-Type -TypeDefinition $typeDefinition -ReferencedAssemblies System.Windows.Forms

$form = [MediaKeyProbeForm]::new()
$null = $form.Handle

$timer = [System.Windows.Forms.Timer]::new()
$timer.Interval = [Math]::Max(1000, $DurationSeconds * 1000)
$timer.Add_Tick({
    $timer.Stop()
    $form.Close()
})

Write-Output "Listening for Windows media-button events for $DurationSeconds seconds."
Write-Output 'Tap the earbuds now: single tap, then double tap, then triple tap if the headset supports it.'
Write-Output "Registered hotkeys: $($form.RegisteredHotkeys -join ', ')"

$timer.Start()
[System.Windows.Forms.Application]::Run($form)

$summary = [pscustomobject]@{
    eventCount = $form.CapturedEvents.Count
    uniqueLabels = @($form.CapturedEvents | Select-Object -ExpandProperty Label -Unique)
    firstEvent = if ($form.CapturedEvents.Count -gt 0) { $form.CapturedEvents[0].TimestampLocal.ToString('O') } else { $null }
    lastEvent = if ($form.CapturedEvents.Count -gt 0) { $form.CapturedEvents[$form.CapturedEvents.Count - 1].TimestampLocal.ToString('O') } else { $null }
}

Write-Output ''
Write-Output 'Probe summary:'
$summary | ConvertTo-Json -Depth 4

if ($FailIfNoEvents -and $form.CapturedEvents.Count -eq 0) {
    exit 1
}

exit 0