package com.lanfps.client

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.lanfps.shared.GameConstants

/**
 * The connect screen.
 *
 * Manual IP entry is the primary path because it is the one that always works:
 * UDP broadcast discovery is a convenience that access-point client isolation or
 * a Windows firewall rule can silently kill. The phone's own Wi-Fi address is
 * shown so a mismatched subnet is obvious at a glance instead of looking like a
 * mysterious timeout.
 */
@SuppressLint("ViewConstructor")
class MenuView(
    context: Context,
    private val state: ClientGameState,
    private val input: InputController,
    private val onConnect: (ip: String, port: Int, nick: String, password: String) -> Unit,
    private val onScan: () -> Unit,
    private val onQuit: () -> Unit,
) : LinearLayout(context) {

    private val nickField: EditText
    private val ipField: EditText
    private val portField: EditText
    private val passwordField: EditText
    private val statusLabel: TextView
    private val serverList: LinearLayout
    private val localIpLabel: TextView
    private val sensLabel: TextView

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.argb(238, 8, 11, 16))
        val pad = UiKit.dp(context, 18f)
        setPadding(pad, pad, pad, pad)
        gravity = Gravity.CENTER_VERTICAL
        // Swallow touches so nothing reaches the game controls behind the menu.
        isClickable = true

        // ---------------------------------------------------------- left panel
        val left = UiKit.panel(context).apply {
            layoutParams = UiKit.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f)
        }

        left.addView(UiKit.title(context, "LAN FPS", 30f, UiKit.ACCENT))
        left.addView(
            UiKit.label(context, "Local network multiplayer  ·  UDP  ·  no internet required", 11f),
        )
        left.addView(UiKit.divider(context))

        val scroll = ScrollView(context).apply {
            layoutParams = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        val form = LinearLayout(context).apply { orientation = VERTICAL }
        scroll.addView(form)

        form.addView(UiKit.label(context, "NICKNAME"))
        nickField = UiKit.field(context, state.nickname, "Player").apply {
            layoutParams = UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                margins = intArrayOf(0, UiKit.dp(context, 4f), 0, UiKit.dp(context, 10f)),
            )
        }
        form.addView(nickField)

        val addrRow = UiKit.row(context)
        val ipCol = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = UiKit.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        ipCol.addView(UiKit.label(context, "SERVER IP (the Windows PC)"))
        ipField = UiKit.field(context, state.serverIp, GameConstants.DEFAULT_SERVER_IP).apply {
            layoutParams = UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                margins = intArrayOf(0, UiKit.dp(context, 4f), UiKit.dp(context, 8f), 0),
            )
        }
        ipCol.addView(ipField)
        addrRow.addView(ipCol)

        val portCol = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = UiKit.lp(UiKit.dp(context, 96f), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        portCol.addView(UiKit.label(context, "PORT"))
        portField = UiKit.field(context, state.serverPort.toString(), "7777", numeric = true).apply {
            layoutParams = UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                margins = intArrayOf(0, UiKit.dp(context, 4f), 0, 0),
            )
        }
        portCol.addView(portField)
        addrRow.addView(portCol)
        form.addView(addrRow)

        // P0-3 (optional): only needed when the server operator set password=
        // in server.properties; empty means "open server".
        form.addView(UiKit.label(context, "PASSWORD (optional - only if the server asks for one)"))
        passwordField = UiKit.field(context, state.password, "leave empty for open servers").apply {
            layoutParams = UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                margins = intArrayOf(0, UiKit.dp(context, 4f), 0, 0),
            )
        }
        form.addView(passwordField)

        form.addView(UiKit.spacer(context, 14f))

        val buttons = UiKit.row(context)
        buttons.addView(
            UiKit.button(context, "Connect", primary = true) { doConnect() }.apply {
                layoutParams = UiKit.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        buttons.addView(
            UiKit.button(context, "Scan LAN") { onScan() }.apply {
                layoutParams = UiKit.lp(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f,
                    margins = intArrayOf(UiKit.dp(context, 8f), 0, 0, 0),
                )
            },
        )
        form.addView(buttons)

        form.addView(UiKit.divider(context))

        // ---- look sensitivity -------------------------------------------------
        sensLabel = UiKit.label(context, "", 11f)
        form.addView(sensLabel)
        val seek = SeekBar(context).apply {
            max = 100
            progress = sensToProgress(input.sensitivity)
            layoutParams = UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                margins = intArrayOf(0, UiKit.dp(context, 2f), 0, UiKit.dp(context, 2f)),
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    input.sensitivity = progressToSens(p)
                    updateSensLabel()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        form.addView(seek)
        updateSensLabel()

        val invert = CheckBox(context).apply {
            text = "Invert vertical look"
            setTextColor(UiKit.TEXT_DIM)
            isChecked = input.invertY
            setOnCheckedChangeListener { _, checked -> input.invertY = checked }
        }
        form.addView(invert)

        left.addView(scroll)

        statusLabel = UiKit.mono(context, "", 11f, UiKit.TEXT_DIM).apply {
            layoutParams = UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                margins = intArrayOf(0, UiKit.dp(context, 8f), 0, 0),
            )
        }
        left.addView(statusLabel)

        addView(left)

        // --------------------------------------------------------- right panel
        val right = UiKit.panel(context).apply {
            layoutParams = UiKit.lp(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f,
                margins = intArrayOf(UiKit.dp(context, 14f), 0, 0, 0),
            )
        }

        right.addView(UiKit.title(context, "SERVERS ON THIS WI-FI", 14f))
        localIpLabel = UiKit.mono(context, "", 10.5f, UiKit.TEXT_DIM)
        right.addView(localIpLabel)
        right.addView(UiKit.divider(context))

        val listScroll = ScrollView(context).apply {
            layoutParams = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        serverList = LinearLayout(context).apply { orientation = VERTICAL }
        listScroll.addView(serverList)
        right.addView(listScroll)

        right.addView(UiKit.divider(context))
        right.addView(
            UiKit.label(
                context,
                "Tip: on the PC run  ipconfig  and use the IPv4 address of the Wi-Fi adapter. " +
                    "Both phones and the PC must be on the same network, and UDP port " +
                    "${GameConstants.DEFAULT_UDP_PORT} must be allowed through the Windows firewall.",
                10.5f,
            ),
        )
        right.addView(
            UiKit.button(context, "Quit") { onQuit() }.apply {
                layoutParams = UiKit.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    margins = intArrayOf(0, UiKit.dp(context, 10f), 0, 0),
                )
            },
        )

        addView(right)

        refreshServers()
        refreshLocalIp()
    }

    private fun sensToProgress(s: Float): Int {
        val t = (s - InputController.MIN_SENSITIVITY) /
            (InputController.MAX_SENSITIVITY - InputController.MIN_SENSITIVITY)
        return (t * 100f).toInt().coerceIn(0, 100)
    }

    private fun progressToSens(p: Int): Float =
        InputController.MIN_SENSITIVITY +
            (InputController.MAX_SENSITIVITY - InputController.MIN_SENSITIVITY) * (p / 100f)

    private fun updateSensLabel() {
        sensLabel.text = "LOOK SENSITIVITY  ·  %.2f deg/dp".format(input.sensitivity)
    }

    private fun doConnect() {
        val nick = nickField.text.toString().trim().ifEmpty { "Player" }
            .take(GameConstants.MAX_NICKNAME_LENGTH)
        val ip = ipField.text.toString().trim()
        val port = portField.text.toString().trim().toIntOrNull()
            ?: GameConstants.DEFAULT_UDP_PORT
        val password = passwordField.text.toString().trim().take(32)

        if (ip.isEmpty()) {
            setStatus("Enter the server IP address, e.g. ${GameConstants.DEFAULT_SERVER_IP}", true)
            return
        }
        if (port !in 1..65535) {
            setStatus("Port must be between 1 and 65535", true)
            return
        }
        onConnect(ip, port, nick, password)
    }

    fun setStatus(msg: String, error: Boolean = false) {
        statusLabel.text = msg
        statusLabel.setTextColor(if (error) UiKit.BAD else UiKit.TEXT_DIM)
    }

    /** Rebuilds the discovered-server list from [ClientGameState]. */
    fun refreshServers() {
        serverList.removeAllViews()
        val servers = state.discoveredServers()
        if (servers.isEmpty()) {
            serverList.addView(
                UiKit.label(
                    context,
                    "No servers found yet.\n\nPress “Scan LAN”, or just type the IP on the left — " +
                        "typing it always works even when broadcast is blocked.",
                    11.5f,
                ),
            )
            return
        }
        for (s in servers) {
            val rowView = UiKit.panel(context).apply {
                background = UiKit.rounded(
                    UiKit.PANEL_HI, UiKit.dp(context, 6f), UiKit.dp(context, 1f), UiKit.LINE,
                )
                layoutParams = UiKit.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    margins = intArrayOf(0, 0, 0, UiKit.dp(context, 8f)),
                )
                isClickable = true
                setOnClickListener {
                    ipField.setText(s.ip)
                    portField.setText(s.port.toString())
                    setStatus("Selected ${s.name} at ${s.ip}:${s.port}")
                }
            }
            rowView.addView(UiKit.title(context, s.name, 14f))
            rowView.addView(
                UiKit.mono(
                    context,
                    "${s.ip}:${s.port}   ${s.mode}   ${s.players}/${s.maxPlayers} players",
                    11f,
                    UiKit.TEXT_DIM,
                ),
            )
            serverList.addView(rowView)
        }
    }

    fun refreshLocalIp() {
        localIpLabel.text = "this phone: ${localIpv4() ?: "no Wi-Fi address"}"
    }

    /** First non-loopback IPv4 address — the phone's address on the Wi-Fi. */
    private fun localIpv4(): String? {
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return "${addr.hostAddress}  (${nif.displayName})"
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun currentNickname(): String = nickField.text.toString().trim().ifEmpty { "Player" }
}
