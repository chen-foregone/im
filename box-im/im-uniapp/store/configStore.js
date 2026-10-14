import { defineStore } from 'pinia';
import http from '../common/request'

export default defineStore('configStore', {
	state: () => {
		return {
			webrtc: {}
		}
	},
	actions: {
		setConfig(config) {
			this.webrtc = config.webrtc;
		},
		clear() {
			this.webrtc = {};
		},
		async loadConfig() {
			const config = await	http({url: '/system/config'});
			this.setConfig(config);
			console.log("系统配置", config)
				
		}
	}
})