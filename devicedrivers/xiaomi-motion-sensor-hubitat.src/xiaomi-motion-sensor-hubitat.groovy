/**
 *  Xiaomi "Original" Motion Sensor
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.7
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
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
 *  Code reworked for use with Hubitat Elevation hub by veeceeoh
 *
 *  Known issues:
 *  + Xiaomi devices send reports based on changes, and a status report every 50-60 minutes. These settings cannot be adjusted.
 *  + The battery level / voltage is not reported at pairing. Wait for the first status report, 50-60 minutes after pairing.
 *  + Pairing Xiaomi devices can be difficult as they were not designed to use with a Hubitat hub.
 *    Holding the sensor's reset button until the LED blinks will start pairing mode.
 *    3 quick flashes indicates success, while one long flash means pairing has not started yet.
 *    In either case, keep the sensor "awake" by short-pressing the reset button repeatedly, until recognized by Hubitat.
 *  + The connection can be dropped without warning. To reconnect, put Hubitat in "Discover Devices" mode, then short-press
 *    the sensor's reset button, and wait for the LED - 3 quick flashes indicates reconnection. Otherwise, short-press again.
 *
 */

metadata {
	definition (name: "Xiaomi Original Motion Sensor", namespace: "veeceeoh", author: "veeceeoh") {
		capability "Motion Sensor"
		capability "Sensor"
		capability "Battery"

		attribute "lastCheckin", "String"
		attribute "lastMotion", "String"
		attribute "lastInactive", "String"
		attribute "batteryLastReplaced", "String"

		//fingerprint for Xioami "original" Motion Sensor
		fingerprint profileId: "0104", deviceId: "0104", inClusters: "0000, 0003, FFFF, 0019", outClusters: "0000, 0004, 0003, 0006, 0008, 0005, 0019", manufacturer: "LUMI", model: "lumi.sensor_motion", deviceJoinName: "Xiaomi Motion"

		command "resetBatteryReplacedDate"
		command "resetToMotionInactive"
	}

	preferences {
		//Reset to No Motion Config
		input "motionreset", "number", title: "After motion is detected, wait ___ second(s) until resetting to inactive state:", description: "Default = 61 seconds (Hardware resets at 60 seconds)", range: "1..7200"
		//Battery Voltage Range
 		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.7)", description: "Default = 2.5 Volts", type: "decimal", range: "2..2.7"
 		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.8 to 3.4)", description: "Default = 3.0 Volts", type: "decimal", range: "2.8..3.4"
 		//Logging Message Config
 		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
 		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	def cluster = description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	Map map = [:]

	// lastCheckin can be used with webCoRE
	sendEvent(name: "lastCheckin", value: now())

	displayDebugLog("Parsing message: ${description}")

	// Send message data to appropriate parsing function based on the type of report
	if (cluster == "0406")
		// Parse motion detected report
		map = parseMotion()
	else if (cluster == "0000" & attrId == "0005")
		displayDebugLog("Reset button was short-pressed")
	else if (cluster == "0000" & (attrId == "FF01" || attrId == "FF02"))
		// Parse battery level from hourly announcement message
		map = parseBattery(valueHex)
	else
		displayDebugLog("Unable to parse ${description}")

	if (map != [:]) {
		displayInfoLog(map.descriptionText)
		displayDebugLog("Creating event $map")
		return createEvent(map)
	} else
		return [:]
}

// Parse motion active report
private parseMotion() {
	def seconds = motionreset ? motionreset : 61
	// The sensor only sends a motion detected message so reset to motion inactive is performed in code
	runIn(seconds, resetToMotionInactive)
	sendEvent(name: "lastMotion", value: now())
	return [
		name: 'motion',
		value: 'active',
		isStateChange: true,
		descriptionText: "Detected motion",
	]
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
	def result = [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		isStateChange: true,
		descriptionText: descText
	]
	return result
}

// If currently in 'active' motion detected state, resetToMotionInactive() resets to 'inactive' state and displays 'no motion'
def resetToMotionInactive() {
	if (device.currentState('motion')?.value == "active") {
		def seconds = motionreset ? motionreset : 61
		def descText = "Reset to motion inactive after ${seconds} seconds"
		sendEvent(
			name:'motion',
			value:'inactive',
			isStateChange: true,
			descriptionText: descText
		)
		displayInfoLog(descText)
	}
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

// installed() runs just after a sensor is paired
def installed() {
	state.prefsSetCount = 0
	displayDebugLog("Installing")
}

// configure() runs after installed() when a sensor is paired
def configure() {
	displayInfoLog("Configuring")
	init()
	state.prefsSetCount = 1
	return
}

// updated() will run every time user saves preferences
def updated() {
	displayInfoLog("Updating preference settings")
	init()
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
}

def init() {
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
}
