package org.nextcolor.dashcamviewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Material Symbols Rounded, Google. Apache License 2.0.
// https://github.com/google/material-design-icons
internal object AppIcons {
    val Camera: ImageVector by lazy {
        ImageVector.Builder("Camera", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h480q33 0 56.5 23.5T720-720v180l126-126q10-10 22-5t12 19v344q0 14-12 19t-22-5L720-420v180q0 33-23.5 56.5T640-160H160Zm0-80h480v-480H160v480Zm0 0v-480 480Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Recordings: ImageVector by lazy {
        ImageVector.Builder("Recordings", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M701-535q14-9 14-25t-14-25L506-710q-15-10-30.5-1.5T460-685v250q0 18 15.5 26.5T506-410l195-125ZM320-240q-33 0-56.5-23.5T240-320v-480q0-33 23.5-56.5T320-880h480q33 0 56.5 23.5T880-800v480q0 33-23.5 56.5T800-240H320Zm0-80h480v-480H320v480ZM160-80q-33 0-56.5-23.5T80-160v-520q0-17 11.5-28.5T120-720q17 0 28.5 11.5T160-680v520h520q17 0 28.5 11.5T720-120q0 17-11.5 28.5T680-80H160Zm160-720v480-480Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Download: ImageVector by lazy {
        ImageVector.Builder("Download", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M480-337q-8 0-15-2.5t-13-8.5L308-492q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T365-549l75 75v-286q0-17 11.5-28.5T480-800q17 0 28.5 11.5T520-760v286l75-75q12-12 28.5-11.5T652-548q11 12 11.5 28T652-492L508-348q-6 6-13 8.5t-15 2.5ZM240-160q-33 0-56.5-23.5T160-240v-80q0-17 11.5-28.5T200-360q17 0 28.5 11.5T240-320v80h480v-80q0-17 11.5-28.5T760-360q17 0 28.5 11.5T800-320v80q0 33-23.5 56.5T720-160H240Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Settings: ImageVector by lazy {
        ImageVector.Builder("Settings", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M433-80q-27 0-46.5-18T363-142l-9-66q-13-5-24.5-12T307-235l-62 26q-25 11-50 2t-39-32l-47-82q-14-23-8-49t27-43l53-40q-1-7-1-13.5v-27q0-6.5 1-13.5l-53-40q-21-17-27-43t8-49l47-82q14-23 39-32t50 2l62 26q11-8 23-15t24-12l9-66q4-26 23.5-44t46.5-18h94q27 0 46.5 18t23.5 44l9 66q13 5 24.5 12t22.5 15l62-26q25-11 50-2t39 32l47 82q14 23 8 49t-27 43l-53 40q1 7 1 13.5v27q0 6.5-2 13.5l53 40q21 17 27 43t-8 49l-48 82q-14 23-39 32t-50-2l-60-26q-11 8-23 15t-24 12l-9 66q-4 26-23.5 44T527-80h-94Zm7-80h79l14-106q31-8 57.5-23.5T639-327l99 41 39-68-86-65q5-14 7-29.5t2-31.5q0-16-2-31.5t-7-29.5l86-65-39-68-99 42q-22-23-48.5-38.5T533-694l-13-106h-79l-14 106q-31 8-57.5 23.5T321-633l-99-41-39 68 86 64q-5 15-7 30t-2 32q0 16 2 31t7 30l-86 65 39 68 99-42q22 23 48.5 38.5T427-266l13 106Zm42-180q58 0 99-41t41-99q0-58-41-99t-99-41q-59 0-99.5 41T342-480q0 58 40.5 99t99.5 41Zm-2-140Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Wifi: ImageVector by lazy {
        ImageVector.Builder("Wifi", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M480-120q-42 0-71-29t-29-71q0-42 29-71t71-29q42 0 71 29t29 71q0 42-29 71t-71 29Zm0-440q75 0 142.5 24T745-470q20 15 20.5 39.5T748-388q-17 17-42 17.5T661-384q-38-26-84-41t-97-15q-51 0-97 15t-84 41q-20 14-45 13t-42-18q-17-18-17-42.5t20-39.5q55-42 122.5-65.5T480-560Zm0-240q125 0 235.5 41T914-643q20 17 21 42t-17 43q-17 17-42 17.5T831-556q-72-59-161.5-91.5T480-680q-100 0-189.5 32.5T129-556q-20 16-45 15.5T42-558q-18-18-17-43t21-42q88-75 198.5-116T480-800Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Close: ImageVector by lazy {
        ImageVector.Builder("Close", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M480-424 284-228q-11 11-28 11t-28-11q-11-11-11-28t11-28l196-196-196-196q-11-11-11-28t11-28q11-11 28-11t28 11l196 196 196-196q11-11 28-11t28 11q11 11 11 28t-11 28L536-480l196 196q11 11 11 28t-11 28q-11 11-28 11t-28-11L480-424Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Play: ImageVector by lazy {
        ImageVector.Builder("Play", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M320-273v-414q0-17 12-28.5t28-11.5q5 0 10.5 1.5T381-721l326 207q9 6 13.5 15t4.5 19q0 10-4.5 19T707-446L381-239q-5 3-10.5 4.5T360-233q-16 0-28-11.5T320-273Zm80-207Zm0 134 210-134-210-134v268Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Stop: ImageVector by lazy {
        ImageVector.Builder("Stop", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M240-320v-320q0-33 23.5-56.5T320-720h320q33 0 56.5 23.5T720-640v320q0 33-23.5 56.5T640-240H320q-33 0-56.5-23.5T240-320Zm80 0h320v-320H320v320Zm160-160Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Photo: ImageVector by lazy {
        ImageVector.Builder("Photo", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M480-260q75 0 127.5-52.5T660-440q0-75-52.5-127.5T480-620q-75 0-127.5 52.5T300-440q0 75 52.5 127.5T480-260Zm0-80q-42 0-71-29t-29-71q0-42 29-71t71-29q42 0 71 29t29 71q0 42-29 71t-71 29ZM160-120q-33 0-56.5-23.5T80-200v-480q0-33 23.5-56.5T160-760h126l50-54q11-12 26.5-19t32.5-7h170q17 0 32.5 7t26.5 19l50 54h126q33 0 56.5 23.5T880-680v480q0 33-23.5 56.5T800-120H160Zm0-80h640v-480H638l-73-80H395l-73 80H160v480Zm320-240Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Shield: ImageVector by lazy {
        ImageVector.Builder("Shield", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M480-84q-7 0-13-1t-12-3q-135-45-215-166.5T160-516v-189q0-25 14.5-45t37.5-29l240-90q14-5 28-5t28 5l240 90q23 9 37.5 29t14.5 45v189q0 140-80 261.5T505-88q-6 2-12 3t-13 1Zm0-80q104-33 172-132t68-220v-189l-240-90-240 90v189q0 121 68 220t172 132Zm0-316Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Refresh: ImageVector by lazy {
        ImageVector.Builder("Refresh", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M480-160q-134 0-227-93t-93-227q0-134 93-227t227-93q69 0 132 28.5T720-690v-70q0-17 11.5-28.5T760-800q17 0 28.5 11.5T800-760v200q0 17-11.5 28.5T760-520H560q-17 0-28.5-11.5T520-560q0-17 11.5-28.5T560-600h128q-32-56-87.5-88T480-720q-100 0-170 70t-70 170q0 100 70 170t170 70q68 0 124.5-34.5T692-367q8-14 22.5-19.5t29.5-.5q16 5 23 21t-1 30q-41 80-117 128t-169 48Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Share: ImageVector by lazy {
        ImageVector.Builder("Share", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M720-80q-50 0-85-35t-35-85q0-7 1-14.5t3-13.5L322-392q-17 15-38 23.5t-44 8.5q-50 0-85-35t-35-85q0-50 35-85t85-35q23 0 44 8.5t38 23.5l282-164q-2-6-3-13.5t-1-14.5q0-50 35-85t85-35q50 0 85 35t35 85q0 50-35 85t-85 35q-23 0-44-8.5T638-672L356-508q2 6 3 13.5t1 14.5q0 7-1 14.5t-3 13.5l282 164q17-15 38-23.5t44-8.5q50 0 85 35t35 85q0 50-35 85t-85 35Zm0-640q17 0 28.5-11.5T760-760q0-17-11.5-28.5T720-800q-17 0-28.5 11.5T680-760q0 17 11.5 28.5T720-720ZM240-440q17 0 28.5-11.5T280-480q0-17-11.5-28.5T240-520q-17 0-28.5 11.5T200-480q0 17 11.5 28.5T240-440Zm480 280q17 0 28.5-11.5T760-200q0-17-11.5-28.5T720-240q-17 0-28.5 11.5T680-200q0 17 11.5 28.5T720-160Zm0-600ZM240-480Zm480 280Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Chevron: ImageVector by lazy {
        ImageVector.Builder("Chevron", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M504-480 348-636q-11-11-11-28t11-28q11-11 28-11t28 11l184 184q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L404-268q-11 11-28 11t-28-11q-11-11-11-28t11-28l156-156Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Expand: ImageVector by lazy {
        ImageVector.Builder("Expand", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M480-362q-8 0-15-2.5t-13-8.5L268-557q-11-11-11-28t11-28q11-11 28-11t28 11l156 156 156-156q11-11 28-11t28 11q11 11 11 28t-11 28L508-373q-6 6-13 8.5t-15 2.5Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Lock: ImageVector by lazy {
        ImageVector.Builder("Lock", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M240-80q-33 0-56.5-23.5T160-160v-400q0-33 23.5-56.5T240-640h40v-80q0-83 58.5-141.5T480-920q83 0 141.5 58.5T680-720v80h40q33 0 56.5 23.5T800-560v400q0 33-23.5 56.5T720-80H240Zm0-80h480v-400H240v400Zm240-120q33 0 56.5-23.5T560-360q0-33-23.5-56.5T480-440q-33 0-56.5 23.5T400-360q0 33 23.5 56.5T480-280ZM360-640h240v-80q0-50-35-85t-85-35q-50 0-85 35t-35 85v80ZM240-160v-400 400Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Visibility: ImageVector by lazy {
        ImageVector.Builder("Visibility", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M480-320q75 0 127.5-52.5T660-500q0-75-52.5-127.5T480-680q-75 0-127.5 52.5T300-500q0 75 52.5 127.5T480-320Zm0-72q-45 0-76.5-31.5T372-500q0-45 31.5-76.5T480-608q45 0 76.5 31.5T588-500q0 45-31.5 76.5T480-392Zm0 192q-134 0-244.5-72T61-462q-5-9-7.5-18.5T51-500q0-10 2.5-19.5T61-538q64-118 174.5-190T480-800q134 0 244.5 72T899-538q5 9 7.5 18.5T909-500q0 10-2.5 19.5T899-462q-64 118-174.5 190T480-200Zm0-300Zm0 220q113 0 207.5-59.5T832-500q-50-101-144.5-160.5T480-720q-113 0-207.5 59.5T128-500q50 101 144.5 160.5T480-280Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val VisibilityOff: ImageVector by lazy {
        ImageVector.Builder("VisibilityOff", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M607-627q29 29 42.5 66t9.5 76q0 15-11 25.5T622-449q-15 0-25.5-10.5T586-485q5-26-3-50t-25-41q-17-17-41-26t-51-4q-15 0-25.5-11T430-643q0-15 10.5-25.5T466-679q38-4 75 9.5t66 42.5Zm-127-93q-19 0-37 1.5t-36 5.5q-17 3-30.5-5T358-742q-5-16 3.5-31t24.5-18q23-5 46.5-7t47.5-2q137 0 250.5 72T904-534q4 8 6 16.5t2 17.5q0 9-1.5 17.5T905-466q-18 40-44.5 75T802-327q-12 11-28 9t-26-16q-10-14-8.5-30.5T753-392q24-23 44-50t35-58q-50-101-144.5-160.5T480-720Zm0 520q-134 0-245-72.5T60-463q-5-8-7.5-17.5T50-500q0-10 2-19t7-18q20-40 46.5-76.5T166-680l-83-84q-11-12-10.5-28.5T84-820q11-11 28-11t28 11l680 680q11 11 11.5 27.5T820-84q-11 11-28 11t-28-11L624-222q-35 11-71 16.5t-73 5.5ZM222-624q-29 26-53 57t-41 67q50 101 144.5 160.5T480-280q20 0 39-2.5t39-5.5l-36-38q-11 3-21 4.5t-21 1.5q-75 0-127.5-52.5T300-500q0-11 1.5-21t4.5-21l-84-82Zm319 93Zm-151 75Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Folder: ImageVector by lazy {
        ImageVector.Builder("Folder", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h207q16 0 30.5 6t25.5 17l57 57h320q33 0 56.5 23.5T880-640v400q0 33-23.5 56.5T800-160H160Zm0-80h640v-400H447l-80-80H160v480Zm0 0v-480 480Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Check: ImageVector by lazy {
        ImageVector.Builder("Check", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "m382-354 339-339q12-12 28-12t28 12q12 12 12 28.5T777-636L410-268q-12 12-28 12t-28-12L182-440q-12-12-11.5-28.5T183-497q12-12 28.5-12t28.5 12l142 143Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Disconnect: ImageVector by lazy {
        ImageVector.Builder("Disconnect", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "m625-449-71-71h46q17 0 28.5 11.5T640-480q0 10-4 18t-11 13ZM820-84q-11 11-28 11t-28-11L84-764q-11-11-11-28t11-28q11-11 28-11t28 11l680 680q11 11 11 28t-11 28ZM280-280q-83 0-141.5-58.5T80-480q0-69 42-123t108-71l74 74h-24q-50 0-85 35t-35 85q0 50 35 85t85 35h120q17 0 28.5 11.5T440-320q0 17-11.5 28.5T400-280H280Zm80-160q-17 0-28.5-11.5T320-480q0-17 11.5-28.5T360-520h25l79 80H360Zm380 112q-9-14-6.5-30t16.5-25q23-17 36.5-42t13.5-55q0-50-35-85t-85-35H560q-17 0-28.5-11.5T520-640q0-17 11.5-28.5T560-680h120q83 0 141.5 58.5T880-480q0 49-22.5 91.5T795-318q-14 9-30 6.5T740-328Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Storage: ImageVector by lazy {
        ImageVector.Builder("Storage", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M400-520q17 0 28.5-11.5T440-560v-80q0-17-11.5-28.5T400-680q-17 0-28.5 11.5T360-640v80q0 17 11.5 28.5T400-520Zm120 0q17 0 28.5-11.5T560-560v-80q0-17-11.5-28.5T520-680q-17 0-28.5 11.5T480-640v80q0 17 11.5 28.5T520-520Zm120 0q17 0 28.5-11.5T680-560v-80q0-17-11.5-28.5T640-680q-17 0-28.5 11.5T600-640v80q0 17 11.5 28.5T640-520ZM240-80q-33 0-56.5-23.5T160-160v-447q0-16 6-30.5t17-25.5l194-194q11-11 25.5-17t30.5-6h287q33 0 56.5 23.5T800-800v640q0 33-23.5 56.5T720-80H240Zm0-80h480v-640H434L240-606v446Zm0 0h480-480Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Info: ImageVector by lazy {
        ImageVector.Builder("Info", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M480-280q17 0 28.5-11.5T520-320v-160q0-17-11.5-28.5T480-520q-17 0-28.5 11.5T440-480v160q0 17 11.5 28.5T480-280Zm0-320q17 0 28.5-11.5T520-640q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640q0 17 11.5 28.5T480-600Zm0 520q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val SwitchCamera: ImageVector by lazy {
        ImageVector.Builder("SwitchCamera", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M320-280q-33 0-56.5-23.5T240-360v-240q0-33 23.5-56.5T320-680h40l28-28q6-6 13.5-9t15.5-3h126q8 0 15.5 3t13.5 9l28 28h40q33 0 56.5 23.5T720-600v240q0 33-23.5 56.5T640-280H320Zm0-80h320v-240H320v240Zm160-40q33 0 56.5-23.5T560-480q0-33-23.5-56.5T480-560q-33 0-56.5 23.5T400-480q0 33 23.5 56.5T480-400Zm0-80Zm0 480Q304 0 171.5-110.5T7-389q-3-17 6.5-31T40-436q17-2 30 8.5T86-400q26 131 124.5 219T444-82l-34-34q-11-11-11-28t11-28q11-11 28-11t28 11L598-40q7 7 4.5 16T591-13q-27 7-54.5 10T480 0Zm0-960q176 0 308.5 110T953-571q3 17-6.5 31T920-524q-17 2-30-8.5T874-560q-26-131-124.5-219T516-878l34 34q11 11 11 28t-11 28q-11 11-28 11t-28-11L362-920q-7-7-4.5-16t11.5-11q27-7 54.5-10t56.5-3Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Delete: ImageVector by lazy {
        ImageVector.Builder("Delete", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M280-120q-33 0-56.5-23.5T200-200v-520q-17 0-28.5-11.5T160-760q0-17 11.5-28.5T200-800h160q0-17 11.5-28.5T400-840h160q17 0 28.5 11.5T600-800h160q17 0 28.5 11.5T800-760q0 17-11.5 28.5T760-720v520q0 33-23.5 56.5T680-120H280Zm400-600H280v520h400v-520ZM400-280q17 0 28.5-11.5T440-320v-280q0-17-11.5-28.5T400-640q-17 0-28.5 11.5T360-600v280q0 17 11.5 28.5T400-280Zm160 0q17 0 28.5-11.5T600-320v-280q0-17-11.5-28.5T560-640q-17 0-28.5 11.5T520-600v280q0 17 11.5 28.5T560-280ZM280-720v520-520Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Tune: ImageVector by lazy {
        ImageVector.Builder("Tune", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M480-120q-17 0-28.5-11.5T440-160v-160q0-17 11.5-28.5T480-360q17 0 28.5 11.5T520-320v40h280q17 0 28.5 11.5T840-240q0 17-11.5 28.5T800-200H520v40q0 17-11.5 28.5T480-120Zm-320-80q-17 0-28.5-11.5T120-240q0-17 11.5-28.5T160-280h160q17 0 28.5 11.5T360-240q0 17-11.5 28.5T320-200H160Zm160-160q-17 0-28.5-11.5T280-400v-40H160q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h120v-40q0-17 11.5-28.5T320-600q17 0 28.5 11.5T360-560v160q0 17-11.5 28.5T320-360Zm160-80q-17 0-28.5-11.5T440-480q0-17 11.5-28.5T480-520h320q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440H480Zm160-160q-17 0-28.5-11.5T600-640v-160q0-17 11.5-28.5T640-840q17 0 28.5 11.5T680-800v40h120q17 0 28.5 11.5T840-720q0 17-11.5 28.5T800-680H680v40q0 17-11.5 28.5T640-600Zm-480-80q-17 0-28.5-11.5T120-720q0-17 11.5-28.5T160-760h320q17 0 28.5 11.5T520-720q0 17-11.5 28.5T480-680H160Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
    val Clock: ImageVector by lazy {
        ImageVector.Builder("Clock", 24.dp, 24.dp, 960.0f, 960.0f)
            .apply {
                addGroup(translationY = 960.0f)
                addPath(
                    addPathNodes(
                        "M520-496v-144q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640v159q0 8 3 15.5t9 13.5l132 132q11 11 28 11t28-11q11-11 11-28t-11-28L520-496ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-400Zm0 320q133 0 226.5-93.5T800-480q0-133-93.5-226.5T480-800q-133 0-226.5 93.5T160-480q0 133 93.5 226.5T480-160Z"
                    ),
                    fill = SolidColor(Color.Black),
                )
                clearGroup()
            }
            .build()
    }
}
