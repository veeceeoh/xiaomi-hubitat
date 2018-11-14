/**
 *  Xiaomi Aqara Vibration Sensor
 *  Model DJT11LM
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.5b
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
 *  Reworked for use with Hubitat Elevation hub by veeceeoh
 *  Additional contributions to code by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, oltman, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
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
 *    flashes 3 times, the Aqara Motion Sensor should be reconnected and will resume reporting as normal
 */

metadata {
	definition (name: "Xiaomi Aqara Vibration Sensor", namespace: "veeceeoh", author: "veeceeoh") {
		capability "Acceleration Sensor"
		capability "Battery"
		capability "Configuration"
		capability "Contact Sensor"
		capability "Motion Sensor"
		capability "PushableButton"
		capability "Refresh"
		capability "Sensor"
		capability "Three Axis"

		attribute "activityLevel", "String"
		attribute "angleX", "number"
		attribute "angleY", "number"
		attribute "angleZ", "number"
		attribute "batteryRuntime", "String"
		attribute "lastCheckin", "String"
		attribute "lastCheckinTime", "Date"
		attribute "lastDrop", "String"
		attribute "lastDropTime", "Date"
		attribute "lastStationary", "String"
		attribute "lastStationaryTime", "Date"
		attribute "lastTilt", "String"
		attribute "lastTiltTime", "Date"
		attribute "lastVibration", "String"
		attribute "lastVibrationTime", "Date"
		attribute "sensitivityLevel", "String"
		attribute "sensorStatus", "enum", ["vibrating", "tilted", "dropped", "Stationary"]
		attribute "tiltAngle", "String"

		fingerprint profileId: "0104", deviceId: "000A", inClusters: "0000,0003,0019,0101", outClusters: "0000,0004,0003,0005,0019,0101", manufacturer: "LUMI", model: "lumi.vibration.aq1"

		command "resetBatteryRuntime"
		command "changeSensitivity"
		command "setOpenPosition"
		command "setClosedPosition"
	}

	preferences {
		//Reset to No Motion Config
		input "motionreset", "number", title: "", description: "Number of seconds to reset Motion = Active when sensor detects vibration/shock (default = 65)", range: "1..7200"
		//Battery Voltage Range
		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.9). Default = 2.9 Volts", description: "", type: "decimal", range: "2..2.9"
		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.95 to 3.4). Default = 3.05 Volts", description: "", type: "decimal", range: "2.95..3.4"
		//Logging Message Config
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
    displayDebugLog "Parsing sensor message: ${description}"
	def cluster = description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def value = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	def eventType
	Map map = [:]

	// lastCheckin can be used with webCoRE and lastCheckinTime used with Hubitat Dashboard
	sendEvent(name: "lastCheckin", value: now())
	sendEvent(name: "lastCheckinTime", value: new Date().toLocaleString())

	// Send message data to appropriate parsing function based on the type of report
	if (attrId == "0055") {
	// Handles vibration (value 01), tilt (value 02), and drop (value 03) event messages
		if (value?.endsWith('0002')) {
			eventType = 2
			parseTiltAngle(value[0..3])
		} else {
			eventType = Integer.parseInt(value,16)
		}
		map = mapSensorEvent(eventType)
	} else if (attrId == "0508") {
		// Handles XYZ Accelerometer values
		map = convertAccelValues(value)
	} else if (attrId == "0505") {
		map = mapActivityLevel(value)
	} else if (cluster == "0000" & attrId == "0005") {
		displayDebugLog "Reset button was short-pressed"
		// Parse battery level from longer type of announcement message
		map = (value.size() > 60) ? parseBattery(value.split('FF42')[1]) : [:]
	} else if (cluster == "0000" & (attrId == "FF01" || attrId == "FF02")) {
		// Parse battery level from hourly announcement message
		map = (value.size() > 30) ? parseBattery(value) : [:]
	} else if (!(cluster == "0000" & attrId == "0001")) {
		displayDebugLog "Unable to parse ${description}"
	}

	if (map != [:]) {
		displayDebugLog("Creating event $map")
		return createEvent(map)
	} else
		return [:]
}

// Convert XYZ Accelerometer values to 3-Axis angle position
private Map convertAccelValues(value) {
	short x = (short)Integer.parseInt(value[8..11],16)
	short y = (short)Integer.parseInt(value[4..7],16)
	short z = (short)Integer.parseInt(value[0..3],16)
	float Psi = Math.round(Math.atan(x/Math.sqrt(z*z+y*y))*1800/Math.PI)/10
	float Phi = Math.round(Math.atan(y/Math.sqrt(x*x+z*z))*1800/Math.PI)/10
	float Theta = Math.round(Math.atan(z/Math.sqrt(x*x+y*y))*1800/Math.PI)/10
	def descText = ": Calculated angles are Psi = ${Psi}°, Phi = ${Phi}°, Theta = ${Theta}° "
	displayDebugLog(": Raw accelerometer XYZ axis values = $x, $y, $z")
	displayDebugLog(descText)
	sendEvent(name: "angleX", value: Psi, displayed: false)
	sendEvent(name: "angleY", value: Phi, displayed: false)
	sendEvent(name: "angleZ", value: Theta, displayed: false)
	if (!state.closedX || !state.openX)
		displayInfoLog(": Open/Closed position is unknown because Open and/or Closed positions have not been set")
	else {
		def float cX = Float.parseFloat(state.closedX)
		def float cY = Float.parseFloat(state.closedY)
		def float cZ = Float.parseFloat(state.closedZ)
		def float oX = Float.parseFloat(state.openX)
		def float oY = Float.parseFloat(state.openY)
		def float oZ = Float.parseFloat(state.openZ)
		def float e = 10.0 // Sets range for margin of error
		def ocPosition = "unknown"
		if ((Psi < cX + e) && (Psi > cX - e) && (Phi < cY + e) && (Phi > cY - e) && (Theta < cZ + e) && (Theta > cZ - e))
			ocPosition = "closed"
		else if ((Psi < oX + e) && (Psi > oX - e) && (Phi < oY + e) && (Phi > oY - e) && (Theta < oZ + e) && (Theta > oZ - e))
			ocPosition = "open"
		else
			displayDebugLog(": The current calculated angle position does not match either stored open/closed positions")
		sendpositionEvent(ocPosition)
	}
	return [
		name: 'threeAxis',
		value: [Psi, Phi, Theta],
		linkText: getLinkText(device),
		isStateChange: true,
		descriptionText: descText,
	]
}

// Handles Recent Activity level value messages
private Map mapActivityLevel(value) {
	def level = Integer.parseInt(value[0..3],16)
	def descText = ": Recent activity level reported at $level"
	displayInfoLog(descText)
	return [
		name: 'activityLevel',
		value: level,
		descriptionText: "$device.displayName$descText",
	]
}

// Create map of values to be used for vibration, tilt, or drop event
private Map mapSensorEvent(value) {
	def seconds = (value == 1 || value == 4) ? (motionreset ? motionreset : 65) : 2
	def time = new Date(now() + (seconds * 1000))
	def statusType = ["Stationary", "Vibration", "Tilt", "Drop", "", ""]
	def eventName = ["", "motion", "acceleration", "button", "motion", "acceleration"]
	def eventType = ["", "active", "active", "pushed", "inactive", "inactive"]
	def eventMessage = [" is stationary", " was vibrating or moving (Motion active)", " was tilted (Acceleration active)", " was dropped (Button pushed)", ": Motion reset to inactive after $seconds seconds", ": Acceleration reset to inactive"]
	if (value < 4) {
		sendEvent(name: "sensorStatus", value: statusType[value], descriptionText: "$device.displayName${eventMessage[value]}", isStateChange: true, displayed: (value == 0) ? true : false)
		updateDateTimeStamp(statusType[value])
	}
	displayInfoLog("${eventMessage[value]}")
	if (value == 0)
		return
	else if (value == 1) {
		runOnce(time, clearmotionEvent)
		state.motionactive = 1
	}
	else if (value == 2)
		runOnce(time, clearaccelEvent)
	else if (value == 3)
		runOnce(time, cleardropEvent)
	return [
		name: eventName[value],
		value: eventType[value],
		descriptionText: "$device.displayName${eventMessage[value]}",
		isStateChange: true,
		displayed: true
	]
}

// Handles tilt angle change message and posts event to update UI tile display
private parseTiltAngle(value) {
	def angle = Integer.parseInt(value,16)
	def descText = ": tilt angle changed by $angle°"
	sendEvent(
		name: 'tiltAngle',
		value: angle,
		descriptionText : "$device.displayName$descText",
		isStateChange:true,
		displayed: true
	)
	displayInfoLog(descText)
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
	def minVolts = voltsmin ? voltsmin : 2.9
	def maxVolts = voltsmax ? voltsmax : 3.05
	def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
	def roundedPct = Math.min(100, Math.round(pct * 100))
	def descText = "Battery level is ${roundedPct}% (${rawVolts} Volts)"
	displayInfoLog(descText)
	return [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		isStateChange: true,
		descriptionText: descText
	]
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
	def newlyPaired = paired ? " for newly paired sensor" : ""
	sendEvent(name: "batteryLastReplaced", value: new Date())
	displayInfoLog("Setting Battery Last Replaced to current date${newlyPaired}")
}

// installed() runs just after a sensor is paired using the "Add a Thing" method in the SmartThings mobile app
def installed() {
	state.prefsSetCount = 0
	displayInfoLog(": Installing")
	init(0)
}

// configure() runs after installed() when a sensor is paired
def configure() {
	displayInfoLog(": Configuring")
	mapSensorEvent(0)
	refresh()
	init()
	state.prefsSetCount = 1
	return
}

// updated() will run twice every time user presses save in preference settings page
def updated() {
	def cmd
	displayInfoLog(": Updating preference settings")
	init()
	cmd = refresh()
	displayInfoLog(": Info message logging enabled")
	displayDebugLog(": Debug message logging enabled")
	return cmd
}

def init() {
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
	sendEvent(name: "numberOfButtons", value: 1, displayed: false)
}

// update lastStationary, lastVibration, lastTilt, or lastDrop to current date/time
def updateDateTimeStamp(timeStampType) {
	displayDebugLog("Setting last${timeStampType} & last${timeStampType}Time to current date/time for webCoRE/dashboard use")
	sendEvent(name: "last${timeStampType}", value: now(), descriptionText: "Updated button${timeStampType} (webCoRE)")
	sendEvent(name: "last${timeStampType}Time", value: new Date().toLocaleString(), descriptionText: "Updated button${timeStampType}Time")
}

def clearmotionEvent() {
	def result = [:]
	if (device.currentState('sensorStatus')?.value == "Vibration")
		mapSensorEvent(0)
	result = mapSensorEvent(4)
	state.motionactive = 0
	displayDebugLog(": Sending event $result")
	sendEvent(result)
}

def clearaccelEvent() {
	def result = [:]
	if (device.currentState('sensorStatus')?.value == "Tilt") {
		if (state.motionactive == 1)
			sendEvent(name: "sensorStatus", value: "Vibration", displayed: false)
		else
			mapSensorEvent(0)
	}
	result = mapSensorEvent(5)
	displayDebugLog(": Sending event $result")
	sendEvent(result)
}

def cleardropEvent() {
	if (device.currentState('sensorStatus')?.value == "Drop") {
		if (state.motionactive == 1)
			sendEvent(name: "sensorStatus", value: "Vibration", displayed: false)
		else
			mapSensorEvent(0)
	}
}

def setClosedPosition() {
	if (device.currentValue('angleX')) {
		state.closedX = device.currentState('angleX').value
		state.closedY = device.currentState('angleY').value
		state.closedZ = device.currentState('angleZ').value
		sendpositionEvent("closed")
		displayInfoLog(": Closed position successfully set")
		displayDebugLog(": Closed position set to $state.closedX°, $state.closedY°, $state.closedZ°")
	}
	else
		displayDebugLog(": Closed position NOT set because no 3-axis accelerometer reports have been received yet")
}

def setOpenPosition() {
	if (device.currentValue('angleX')) {
		state.openX = device.currentState('angleX').value
		state.openY = device.currentState('angleY').value
		state.openZ = device.currentState('angleZ').value
		sendpositionEvent("open")
		displayInfoLog(": Open position successfully set")
		displayDebugLog(": Open position set to $state.openX°, $state.openY°, $state.openZ°")
	}
	else
		displayDebugLog(": Open position NOT set because no 3-axis accelerometer reports have been received yet")
}

def sendpositionEvent(String ocPosition) {
	def descText = ": Calculated position is $ocPosition"
	displayInfoLog(descText)
	sendEvent(
		name: "contact",
		value: ocPosition,
		isStateChange: true,
		descriptionText: "$device.displayName$descText")
}

def changeSensitivity() {
	state.sensitivity = (state.sensitivity < 3) ? state.sensitivity + 1 : 1
	def attrValue = [0x00, 0x15, 0x0B, 0x00]
	def levelText = ["", "Low", "Medium", "High"]
	def descText = ": Sensitivity level set to ${levelText[state.sensitivity]}"
	def cmd = [
        "zigbee.writeAttribute(0x0000, 0xFF0D, 0x20, ${attrValue[state.sensitivity]}, [mfgCode: 0x115F])",
        "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0000 0xFF0D {${attrValue[state.sensitivity]}}",
		"he rattr 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0000 0xFF0D"
	]
	sendHubCommand(new hubitat.device.HubAction("zigbee.writeAttribute(0x0000, 0xFF0D, 0x20, ${attrValue[state.sensitivity]}, [mfgCode: 0x115F])"))
	sendHubCommand(new hubitat.device.HubAction("zigbee.readAttribute(0x0000, 0xFF0D, [mfgCode: 0x115F])"))
//	zigbee.writeAttribute(0x0000, 0xFF0D, 0x20, attrValue[state.sensitivity], [mfgCode: 0x115F])
//	zigbee.readAttribute(0x0000, 0xFF0D, [mfgCode: 0x115F])
	sendEvent(name: "sensitivityLevel", value: levelText[state.sensitivity], isStateChange: true, descriptionText: descText)
	displayInfoLog(descText)
	return cmd
}

private def displayDebugLog(message) {
	if (debugLogging) log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
	if (infoLogging || state.prefsSetCount != 1)
		log.info "${device.displayName}: ${message}"
}

def refresh() {
	def cmd
    displayInfoLog(": Refreshing UI display")
	if (!state.sensitivity) {
		state.sensitivity = 0
		cmd = changeSensitivity()
	}
	if (device.currentValue('tiltAngle') == null)
		sendEvent(name: 'tiltAngle', value: "--", isStateChange: true, displayed: false)
	if (device.currentValue('activityLevel') == null)
		sendEvent(name: 'activityLevel', value: "--", isStateChange: true, displayed: false)
	return cmd
}
