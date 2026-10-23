Name:           clip-sync
Version:        0.0.10
Release:        1%{?dist}
Summary:        Seamless Linux and Android continuity daemon

License:        GPL-3.0
URL:            https://github.com/1171Abhishek/Sync-Project

Requires:       wl-clipboard
Requires:       xclip
Requires:       libnotify
Requires:       xdg-utils
Recommends:     nautilus-python

# Disable automatic debuginfo package generation since Go manages its own binaries
%global debug_package %{nil}

%description
ClipSync is a high-speed, lightweight Linux and Android continuity tool.
Features real-time clipboard sync, automatic OTP detection and auto-copy,
one-key browser tab push, and peer-to-peer file transfer.

%prep
# Pre-compiled binary provided in SOURCES

%build
# Nothing to build; binary built prior to packaging

%install
rm -rf %{buildroot}
mkdir -p %{buildroot}%{_bindir}
mkdir -p %{buildroot}%{_prefix}/lib/systemd/user

install -m 755 %{_sourcedir}/clip-sync %{buildroot}%{_bindir}/clip-sync
install -m 644 %{_sourcedir}/clip-sync.service %{buildroot}%{_prefix}/lib/systemd/user/clip-sync.service

%files
%{_bindir}/clip-sync
%{_prefix}/lib/systemd/user/clip-sync.service

%post
echo ""
echo "=========================================================="
echo "✓ ClipSync installed successfully!"
echo "To start ClipSync automatically in the background on login:"
echo "   systemctl --user enable --now clip-sync"
echo "=========================================================="
echo ""

%changelog
* Fri Oct 16 2026 Abhishek Kumar <abhishek@example.com> - 0.0.10-1
- Release version 0.0.10 with battery and call alerts
