/**
 *  Xiaomi "Original" Button - model WXKG01LM
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.8.5
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Based on SmartThings device handler code by a4refillpad
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, mike.maxwell, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
 *  Reworked and additional code for use with Hubitat Elevation hub by veeceeoh
 *
 *  Known issues:
 *  + Xiaomi devices send reports based on changes, and a status report every 50-60 minutes. These settings cannot be adjusted.
 *  + The battery level / voltage is not reported at pairing. Wait for the first status report, 50-60 minutes after pairing.
 *    However, the Aqara Door/Window sensor battery level can be retrieved immediately with a short-press of the reset button.
 *  + Pairing Xiaomi devices can be difficult as they were not designed to use with a Hubitat hub.
 *    Holding the sensor's reset button until the LED blinks will start pairing mode.
 *    3 quick flashes indicates success, while one long flash means pairing has not started yet.
 *    In either case, keep the sensor "awake" by short-pressing the reset button repeatedly, until recognized by Hubitat.
 *  + The connection can be dropped without warning. To reconnect, put Hubitat in "Discover Devices" mode, and follow
 *    the same steps for pairing. As long as it has not been removed from the Hubitat's device list, when the LED
 *    flashes 3 times, the sensor should be reconnected and will resume reporting as normal
 *
 */

metadata {
	definition (name: "Xiaomi Button", namespace: "veeceeoh", author: "veeceeoh") {
		capability "PushableButton"
		capability "HoldableButton"
		capability "Sensor"
		capability "Battery"

		attribute "lastCheckinEpoch", "String"
		attribute "lastCheckinTime", "String"
		attribute "batteryLastReplaced", "String"
		attribute "buttonPressedEpoch", "String"
		attribute "buttonPressedTime", "String"
		attribute "buttonHeldEpoch", "String"
		attribute "buttonHeldTime", "String"
		attribute "buttonReleasedEpoch", "String"
		attribute "buttonReleasedTime", "String"

		// this fingerprint is identical to the one for Xiaomi "Original" Door/Window Sensor except for model name
		fingerprint endpointId: "01", profileId: "0104", deviceId: "0104", inClusters: "0000,0003,FFFF,0019", outClusters: "0000,0004,0003,0006,0008,0005,0019", manufacturer: "LUMI", model: "lumi.sensor_switch"

		command "resetBatteryReplacedDate"
	}

	preferences {
		//Button Config
		input "waittoHeld", "number", title: "Hold button for ___ seconds to set button 1 'held' state. Default = 1 second", description: "", range: "1..60"
		//Battery Voltage Range
 		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.7). Default = 2.5 Volts", description: "", type: "decimal", range: "2..2.7"
 		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.8 to 3.4). Default = 3.0 Volts", description: "", type: "decimal", range: "2.8..3.4"
 		//Logging Message Config
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
		//Firmware 2.0.5 Compatibility Fix Config
		input name: "oldFirmware", type: "bool", title: "DISABLE 2.0.5 firmware compatibility fix (for users of 2.0.4 or earlier)", description: ""
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	def cluster = description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def encoding = Integer.parseInt(description.split(",").find {it.split(":")[0].trim() == "encoding"}?.split(":")[1].trim(), 16)
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	Map map = [:]

	if (!oldFirmware & valueHex != null & encoding > 0x18 & encoding < 0x3e) {
		displayDebugLog("Data type of payload is little-endian; reversing byte order")
		// Reverse order of bytes in description's payload for LE data types - required for Hubitat firmware 2.0.5 or newer
		valueHex = reverseHexString(valueHex)
	}

	displayDebugLog("Parsing message: ${description}")
	displayDebugLog("Message payload: ${valueHex}")

	// lastCheckinEpoch is for apps that can use Epoch time/date and lastCheckinTime can be used with Hubitat Dashboard
	sendEvent(name: "lastCheckinEpoch", value: now())
	sendEvent(name: "lastCheckinTime", value: new Date().toLocaleString())

	// Send message data to appropriate parsing function based on the type of report
	if (cluster == "0006")
		// Parse button press message
		map = parseButtonMessage(Integer.parseInt(valueHex))
    else if (cluster == "0000" & attrId == "0005") {
		displayDebugLog("Reset button was short-pressed")
		// Parse battery level from longer type of announcement message
		map = (valueHex.size() > 60) ? parseBattery(valueHex.split('FF42')[1]) : [:]
    }
	else if (cluster == "0000" & (attrId == "FF01" || attrId == "FF02"))
		// Parse battery level from hourly announcement message
		map = (valueHex.size() > 30) ? parseBattery(valueHex) : [:]
	else if (!(cluster == "0000" & attrId == "0001"))
		displayDebugLog("Unable to parse message")

	if (map != [:]) {
		displayDebugLog("Creating event $map")
		return createEvent(map)
	} else
		return [:]
}

// Reverses order of bytes in hex string
def reverseHexString(hexString) {
	def reversed = ""
	for (int i = hexString.length(); i > 0; i -= 2) {
		reversed += hexString.substring(i - 2, i )
	}
	return reversed
}

// Parse button message (press, double-click, triple-click, quad-click, and release)
private parseButtonMessage(attrValue) {
	def clickType = ["", "single", "double", "triple", "quadruple", "shizzle"]
	def coreType = (attrValue == 1) ? "Released" : "Pressed"
	def countdown = waittoHeld ?: 1
	attrValue = (attrValue < 5) ? attrValue : 5
	updateDateTimeStamp(coreType)
	// On single-press start heldState countdown but do not generate event
	if (attrValue == 0) {
		runIn((countdown), heldState)
		state.countdownActive = true
		displayDebugLog("Button press detected, starting heldState countdown of $countdown second(s)")
	// On multi-click or release when countdown active generate a pushed event
	} else if (state.countdownActive == true || attrValue > 1) {
		displayInfoLog("Button was ${clickType[attrValue]}-clicked (Button $attrValue pushed)")
		state.countdownActive = false
		return [
			name: 'pushed',
			value: attrValue,
			isStateChange: true,
			descriptionText: "Button was ${clickType[attrValue]}-clicked"
		]
	}
	return [:]
}

//set held state if button has not yet been released after single-press
def heldState() {
	displayDebugLog("heldState countdown finished, checking whether 'held' event should be generated")
	def descText = "Button was held (Button 1 held)"
	if (state.countdownActive == true) {
		state.countdownActive = false
		sendEvent(
			name: 'held',
			value: 1,
			isStateChange: true,
			descriptionText: descText
		)
		displayInfoLog(descText)
		updateDateTimeStamp("Held")
	}
}

// Generate buttonPressedEpoch/Time, buttonHeldEpoch/Time, or buttonReleasedEpoch/Time event for Epoch time/date app or Hubitat dashboard use
def updateDateTimeStamp(timeStampType) {
	displayDebugLog("Setting button${timeStampType}Epoch and button${timeStampType}Time to current date/time")
	sendEvent(name: "button${timeStampType}Epoch", value: now(), descriptionText: "Updated button${timeStampType}Epoch")
	sendEvent(name: "button${timeStampType}Time", value: new Date().toLocaleString(), descriptionText: "Updated button${timeStampType}Time")
}

// Convert raw 4 digit integer voltage value into percentage based on minVolts/maxVolts range
private parseBattery(description) {
	displayDebugLog("Battery parse string = ${description}")
	def MsgLength = description.size()
	def rawValue
	for (int i = 4; i < (MsgLength-3); i+=2) {
		if (description[i..(i+1)] == "21") { // Search for byte preceeding battery voltage bytes
			rawValue = Integer.parseInt((description[(i+4)..(i+5)] + description[(i+2)..(i+3)]),16)
			break
		}
	}
	def rawVolts = rawValue / 1000
	def minVolts = voltsmin ? voltsmin : 2.5
	def maxVolts = voltsmax ? voltsmax : 3.0
	def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
	def roundedPct = Math.min(100, Math.round(pct * 100))
	def descText = "Battery level is ${roundedPct}% (${rawVolts} Volts)"
	displayInfoLog(descText)
	def result = [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		isStateChange: true,
		descriptionText: descText
	]
	return result
}

private def displayDebugLog(message) {
	if (debugLogging) log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
	if (infoLogging || state.prefsSetCount != 1)
		log.info "${device.displayName}: ${message}"
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
	def newlyPaired = paired ? " for newly paired sensor" : ""
	sendEvent(name: "batteryLastReplaced", value: new Date())
	displayInfoLog("Setting Battery Last Replaced to current date${newlyPaired}")
}

// this call is here to avoid Groovy errors when the Push command is used
// it is empty because the Xioami button is non-controllable
def push() {
	displayDebugLog("No action taken on Push Command. This button cannot be controlled.")
}

// this call is here to avoid Groovy errors when the Hold command is used
// it is empty because the Xioami button is non-controllable
def hold() {
	displayDebugLog("No action taken on Hold Command. This button cannot be controlled!")
}

// installed() runs just after a sensor is paired
def installed() {
	state.prefsSetCount = 0
	displayInfoLog("Installing")
}

// configure() runs after installed() when a sensor is paired or reconnected
def configure() {
	displayInfoLog("Configuring")
	displayInfoLog("Number of buttons = 5")
	init()
	state.prefsSetCount = 1
	return
}

// updated() runs every time user saves preferences
def updated() {
	displayInfoLog("Updating preference settings")
	init()
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
}

def init() {
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
	sendEvent(name: "numberOfButtons", value: 5)
	state.countdownActive = false
}
