#include "wintoastlib.h"

namespace {

class GromozekaToastHandler final : public WinToastLib::IWinToastHandler {
public:
    void toastActivated() const override {}
    void toastActivated(int) const override {}
    void toastActivated(std::wstring) const override {}
    void toastDismissed(WinToastDismissalReason) const override {}
    void toastFailed() const override {}
};

}

extern "C" __declspec(dllexport) int gromozeka_notifications_initialize() {
    auto* notifications = WinToastLib::WinToast::instance();
    notifications->setAppName(L"Gromozeka");
    notifications->setAppUserModelId(L"Gromozeka.Client");
    notifications->setShortcutPolicy(WinToastLib::WinToast::SHORTCUT_POLICY_REQUIRE_CREATE);
    WinToastLib::WinToast::WinToastError error;
    return notifications->initialize(&error) ? 1 : -static_cast<int>(error);
}

extern "C" __declspec(dllexport) long long gromozeka_notifications_show(
    const wchar_t* title,
    const wchar_t* message
) {
    if (title == nullptr || message == nullptr) {
        return -1;
    }

    WinToastLib::WinToastTemplate notification(WinToastLib::WinToastTemplate::Text02);
    notification.setTextField(title, WinToastLib::WinToastTemplate::FirstLine);
    notification.setTextField(message, WinToastLib::WinToastTemplate::SecondLine);
    notification.setAudioOption(WinToastLib::WinToastTemplate::Silent);
    notification.setDuration(WinToastLib::WinToastTemplate::Short);

    WinToastLib::WinToast::WinToastError error;
    return WinToastLib::WinToast::instance()->showToast(
        notification,
        new GromozekaToastHandler(),
        &error
    );
}

extern "C" __declspec(dllexport) int gromozeka_notifications_hide(long long notificationId) {
    return WinToastLib::WinToast::instance()->hideToast(notificationId) ? 1 : 0;
}

