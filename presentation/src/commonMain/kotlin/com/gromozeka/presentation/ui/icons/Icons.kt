package com.gromozeka.presentation.ui.icons

import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.gromozeka.presentation.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

object Icons {
    object Default {
        val AccountTree = Res.drawable.msr_account_tree
        val Add = Res.drawable.msr_add
        val ArrowBack = Res.drawable.msr_arrow_back
        val ArrowDownward = Res.drawable.msr_arrow_downward
        val ArrowForward = Res.drawable.msr_arrow_forward
        val ArrowUpward = Res.drawable.msr_arrow_upward
        val AttachFile = Res.drawable.msr_attach_file
        val Bolt = Res.drawable.msr_bolt
        val Book = Res.drawable.msr_book
        val BugReport = Res.drawable.msr_bug_report
        val Build = Res.drawable.msr_build
        val CameraAlt = Res.drawable.msr_photo_camera
        val ChatBubbleOutline = Res.drawable.msr_chat_bubble
        val Check = Res.drawable.msr_check
        val CheckCircle = Res.drawable.msr_check_circle
        val Clear = Res.drawable.msr_close
        val Close = Res.drawable.msr_close
        val Code = Res.drawable.msr_code
        val Compress = Res.drawable.msr_compress
        val ContentCopy = Res.drawable.msr_content_copy
        val Delete = Res.drawable.msr_delete
        val Description = Res.drawable.msr_description
        val DesktopWindows = Res.drawable.msr_desktop_windows
        val DeveloperBoard = Res.drawable.msr_developer_board
        val Download = Res.drawable.msr_download
        val Edit = Res.drawable.msr_edit
        val Error = Res.drawable.msr_error
        val ErrorOutline = Res.drawable.msr_error
        val Extension = Res.drawable.msr_extension
        val ExpandLess = Res.drawable.msr_expand_less
        val ExpandMore = Res.drawable.msr_expand_more
        val Face = Res.drawable.msr_face
        val FiberManualRecord = Res.drawable.msr_fiber_manual_record
        val Folder = Res.drawable.msr_folder
        val FolderOpen = Res.drawable.msr_folder_open
        val FormatListBulleted = Res.drawable.msr_format_list_bulleted
        val Help = Res.drawable.msr_help
        val Home = Res.drawable.msr_home
        val HourglassTop = Res.drawable.msr_hourglass_top
        val Hub = Res.drawable.msr_hub
        val Image = Res.drawable.msr_image
        val Info = Res.drawable.msr_info
        val Inventory2 = Res.drawable.msr_inventory_2
        val KeyboardArrowDown = Res.drawable.msr_keyboard_arrow_down
        val KeyboardArrowRight = Res.drawable.msr_keyboard_arrow_right
        val KeyboardArrowUp = Res.drawable.msr_keyboard_arrow_up
        val KeyboardHide = Res.drawable.msr_keyboard_hide
        val Link = Res.drawable.msr_link
        val ListAlt = Res.drawable.msr_list_alt
        val LocationOn = Res.drawable.msr_location_on
        val MergeType = Res.drawable.msr_merge_type
        val Mic = Res.drawable.msr_mic
        val MicOff = Res.drawable.msr_mic_off
        val OpenInNew = Res.drawable.msr_open_in_new
        val Person = Res.drawable.msr_person
        val PlaylistAddCheck = Res.drawable.msr_playlist_add_check
        val Psychology = Res.drawable.msr_psychology
        val Public = Res.drawable.msr_public
        val Refresh = Res.drawable.msr_refresh
        val Restore = Res.drawable.msr_restore
        val Schedule = Res.drawable.msr_schedule
        val Search = Res.drawable.msr_search
        val SelectAll = Res.drawable.msr_select_all
        val Send = Res.drawable.msr_send
        val Settings = Res.drawable.msr_settings
        val SmartToy = Res.drawable.msr_smart_toy
        val Star = Res.drawable.msr_star
        val Stop = Res.drawable.msr_stop
        val Subject = Res.drawable.msr_subject
        val Tab = Res.drawable.msr_tab
        val Terminal = Res.drawable.msr_terminal
        val TouchApp = Res.drawable.msr_touch_app
        val ViewList = Res.drawable.msr_view_list
        val Visibility = Res.drawable.msr_visibility
        val VisibilityOff = Res.drawable.msr_visibility_off
        val Warning = Res.drawable.msr_warning
    }

    object Filled {
        val Refresh = Default.Refresh
    }

    object AutoMirrored {
        object Filled {
            val ArrowBack = Default.ArrowBack
            val ArrowForward = Default.ArrowForward
            val KeyboardArrowRight = Default.KeyboardArrowRight
            val ListAlt = Default.ListAlt
            val MergeType = Default.MergeType
            val OpenInNew = Default.OpenInNew
            val PlaylistAddCheck = Default.PlaylistAddCheck
            val Send = Default.Send
            val Subject = Default.Subject
            val ViewList = Default.ViewList
        }
    }
}

@Composable
fun Icon(
    imageVector: DrawableResource,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    MaterialIcon(
        painter = painterResource(imageVector),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}
