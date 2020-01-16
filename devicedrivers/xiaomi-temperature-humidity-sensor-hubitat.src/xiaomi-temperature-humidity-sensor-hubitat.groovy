/**
 * Xiaomi "Original" Temperature Humidity Sensor - model RTCGQ01LM
 * & Aqara Temperature Humidity Sensor - model WSDCGQ11LM
 * Device Driver for Hubitat Elevation hub
 * Version 0.9
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License.
 *
 * Based on SmartThings device handler code by a4refillpad
 * With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, mike.maxwell, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
 * Code reworked for use with Hubitat Elevation hub by veeceeoh
 *
 * Known issues:
 * + Xiaomi devices send reports based on changes, and a status report every 50-60 minutes. These settings cannot be adjusted.
 * + The battery level / voltage is not reported at pairing. Wait for the first status report, 50-60 minutes after pairing.
 * + Pairing Xiaomi devices can be difficult as they were not designed to use with a Hubitat hub.
 *  Holding the sensor's reset button until the LED blinks will start pairing mode.
 *  3 quick flashes indicates success, while one long flash means pairing has not started yet.
 *  In either case, keep the sensor "awake" by short-pressing the reset button repeatedly, until recognized by Hubitat.
 * + The connection can be dropped without warning. To reconnect, put Hubitat in "Discover Devices" mode, then short-press
 *  the sensor's reset button, and wait for the LED - 3 quick flashes indicates reconnection. Otherwise, short-press again.
 *
 */

metadata {
	definition (name: "Xiaomi Temperature Humidity Sensor", namespace: "veeceeoh", author: "veeceeoh", importUrl: "https://raw.githubusercontent.com/veeceeoh/xiaomi-hubitat/master/devicedrivers/xiaomi-temperature-humidity-sensor-hubitat.src/xiaomi-temperature-humidity-sensor-hubitat.groovy") {
		capability "Battery"
		capability "PressureMeasurement"
		capability "RelativeHumidityMeasurement"
		capability "Sensor"
		capability "TemperatureMeasurement"

		command "resetBatteryReplacedDate"

		attribute "lastCheckinEpoch", "String"
		attribute "lastCheckinTime", "Date"
		attribute "batteryLastReplaced", "String"

		//fingerprint for Xioami "original" Temperature Humidity Sensor - model RTCGQ01LM
		fingerprint profileId: "0104", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", model: "lumi.sens"
		fingerprint profileId: "0104", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", model: "lumi.sensor_ht"
		//fingerprint for Xioami Aqara Temperature Humidity Sensor - model WSDCGQ11LM
		fingerprint profileId: "0104", inClusters: "0000,0003,FFFF,0402,0403,0405", outClusters: "0000,0004,FFFF", model: "lumi.weather"
	}

	preferences {
		//Temp and Humidity Offsets
		input "tempOffset", "decimal", title:"Temperature Offset", description:"", range:"*..*"
		input "humidityOffset", "decimal", title:"Humidity Offset", description:"", range: "*..*"
		if (getDataValue("modelType") == "Aqara WSDCGQ11LM" || getDataValue("modelType") == "unknown") {
			input name: "pressOffset", type: "decimal", title: "Pressure Offset", description: "", range: "*..*"
			input name: "pressureUnits", type: "enum", title: "Pressure Units (default: mbar)", description: "", options: ["mbar", "kPa", "inHg", "mmHg"], default: "mbar"
		}
		//Battery Voltage Range
		input name: "voltsmin", type: "decimal", title: "Min Volts (0% battery = ___ volts). Default = 2.8 Volts", description: ""
		input name: "voltsmax", type: "decimal", title: "Max Volts (100% battery = ___ volts). Default = 3.05 Volts", description: ""
		//Date/Time Stamp Events Config
		input name: "lastCheckinEnable", type: "bool", title: "Enable custom date/time stamp events for lastCheckin", description: ""
		//Logging Message Config
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	displayDebugLog("Parsing message: $description")
	def result
	if (description?.startsWith('cat')) {
		Map descMap = zigbee.parseDescriptionAsMap(description)
		displayDebugLog("Zigbee parse map of catchall = $descMap")
		displayDebugLog("No action taken on catchall message")
	} else if (description?.startsWith('re')) {
		description = description - "read attr - "
		Map descMap = (description).split(",").inject([:]) {
			map, param ->
			def nameAndValue = param.split(":")
			map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
		}
		// Reverse payload byte order for little-endian data types - required for Hubitat firmware 2.0.5 or newer
		def intEncoding = Integer.parseInt(descMap.encoding, 16)
		if (descMap.value != null && intEncoding > 0x18 && intEncoding < 0x3e) {
			descMap.value = reverseHexString(descMap.value)
			displayDebugLog("Little-endian payload data type; Hex value reversed to: ${descMap.value}")
		}
	// Send message data to appropriate parsing function based on the type of report
		switch (descMap.cluster) {
			case "0000": // Announcement or Check-in report
				if (descMap.attrId == "0005")
					displayDebugLog("Reset button was short-pressed")
				else if (descMap.attrId == "FF01" || descMap.attrId == "FF02") // Hourly check-in report
					result = parseCheckinMessage(descMap.value)
				break
			case "0402": // Temperature report
				result = parseTemperature(descMap.value)
				break
			case "0403": // Pressure report (Aqara model only)
				result = parsePressure(descMap.value)
				break
			case "0405": // Humidity report
				result = parseHumidity(descMap.value)
				break
			default:
				displayDebugLog("Unknown read attribute message")
		}
	}
	if (result) {
		if (result.descriptionText)
			displayInfoLog(result.descriptionText)
		displayDebugLog("Creating event $result")
		return createEvent(result)
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

// Calculate temperature with 0.01 precision in C or F unit as set by hub location settings
private parseTemperature(hexString) {
	float temp = hexStrToSignedInt(hexString)/100
	def tempScale = location.temperatureScale
	def debugText = "Reported temperature: raw = $temp°C"
	if (temp < -50) {
		log.warn "${device.displayName}: Out-of-bounds temperature value received. Battery voltage may be too low."
		return ""
	} else {
		if (tempScale == "F") {
			temp = ((temp * 1.8) + 32)
			debugText += ", converted = $temp°F"
		}
		if (tempOffset) {
			temp = (temp + tempOffset)
			debugText += ", offset = $tempOffset"
		}
		displayDebugLog(debugText)
		temp = temp.round(2)
		return [
			name: 'temperature',
			value: temp,
			unit: "°$tempScale",
			descriptionText: "Temperature is $temp°$tempScale",
			translatable:true
		]
	}
}

// Calculate humidity with 0.1 precision
private parseHumidity(hexString) {
	float humidity = Integer.parseInt(hexString,16)/100
	def debugText = "Reported humidity: raw = $humidity"
	if (humidity > 100) {
		log.warn "${device.displayName}: Out-of-bounds humidity value received. Battery voltage may be too low."
		return ""
	} else {
		if (humidityOffset) {
			debugText += ", offset = $humidityOffset"
			humidity = (humidity + humidityOffset)
		}
		displayDebugLog(debugText)
		humidity = humidity.round(1)
		return [
			name: 'humidity',
			value: humidity,
			unit: "%",
			descriptionText: "Humidity is ${humidity}%",
		]
	}
}

// Parse pressure report
private parsePressure(hexString) {
	float pressureval = Integer.parseInt(hexString[0..3], 16)
	def debugText = "Reported pressure: raw = $pressureval"
	if (!(pressureUnits)) {
		pressureUnits = "mbar"
	}
	switch (pressureUnits) {
		case "mbar":
			pressureval = (pressureval/10) as Float
			pressureval = pressureval.round(1);
			break;
		case "kPa":
			pressureval = (pressureval/100) as Float
			pressureval = pressureval.round(2);
			break;
		case "inHg":
			pressureval = (((pressureval/10) as Float) * 0.0295300)
			pressureval = pressureval.round(2);
			break;
		case "mmHg":
			pressureval = (((pressureval/10) as Float) * 0.750062)
			pressureval = pressureval.round(2);
			break;
	}
	debugText += ", converted = $pressureval $pressureUnits"
	if (pressOffset) {
		debugText += ", offset = $pressOffset"
		pressureval = (pressureval + pressOffset)
	}
	displayDebugLog(debugText)
	pressureval = pressureval.round(2);
	return [
		name: 'pressureMeasurement',
		value: pressureval,
		unit: pressureUnits,
		descriptionText: "Pressure is ${pressureval} ${pressureUnits}"
	]
}

def parseCheckinMessage(hexString) {
	displayDebugLog("Checkin message raw hex string = ${hexString}")
	return parseBattery(hexString)
}

// Convert raw 4 digit integer voltage value into percentage based on minVolts/maxVolts range
private parseBattery(hexString) {
	def hexBattery = (hexString[8..9] + hexString[6..7])
	displayDebugLog("Battery parse string = ${hexBattery}")
	def rawValue = Integer.parseInt(hexBattery,16)
	def rawVolts = rawValue / 1000
	def minVolts = voltsmin ? voltsmin : 2.8
	def maxVolts = voltsmax ? voltsmax : 3.05
	def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
	def roundedPct = Math.min(100, Math.round(pct * 100))
	displayDebugLog("Battery report: $rawVolts Volts, calculating level based on min/max range of $minVolts to $maxVolts")
	def descText = "Battery level is $roundedPct% ($rawVolts Volts)"
	return [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		descriptionText: descText
	]

	// lastCheckinEpoch is for apps that can use Epoch time/date and lastCheckinTime can be used with Hubitat Dashboard
	if (lastCheckinEnable) {
		sendEvent(name: "lastCheckinEpoch", value: now())
		sendEvent(name: "lastCheckinTime", value: new Date().toLocaleString())
	}
	return result
}

// installed() runs just after a sensor is paired
def installed() {
	displayDebugLog("Installing")
	state.prefsSetCount = 0
	init()
}

// configure() runs after installed() when a sensor is paired
def configure() {
	displayInfoLog("Configuring")
	init()
	state.prefsSetCount = 1
}

// updated() will run every time user saves preferences
def updated() {
	displayInfoLog("Updating preference settings")
	init()
	if (lastCheckinEnable)
		displayInfoLog("Last checkin events enabled")
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
}

def init() {
	if (!(getDataValue("modelType")) || getDataValue("modelType") == "unknown") {
		def sensorModel = "unknown"
		if (device.data.model != "") {
			if (device.data.model[5] == "s")  // for model: "lumi.sensor_ht" or "lumi.sens"
				sensorModel = "Xiaomi RTCGQ01LM"
			else if (device.data.model[5] == "w")  // for model: "lumi.weather"
				sensorModel = "Aqara WSDCGQ11LM"
		}
		updateDataValue("modelType", sensorModel)
		displayInfoLog("Detected sensor model is ${sensorModel}")
	}
	if (!device.currentValue('batteryLastReplaced'))
		resetBatteryReplacedDate(true)
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
	def newlyPaired = paired ? " for newly paired sensor" : ""
	sendEvent(name: "batteryLastReplaced", value: new Date().format("MMM dd yyyy", location.timeZone))
	displayInfoLog("Setting Battery Last Replaced to current date${newlyPaired}")
}

private def displayDebugLog(message) {
	if (debugLogging) log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
	if (infoLogging || state.prefsSetCount != 1)
		log.info "${device.displayName}: ${message}"
}
