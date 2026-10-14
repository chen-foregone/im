import { defineStore } from 'pinia';
import http from '../common/request'

export default defineStore('userStore', {
	state: () => {
		return {
			userInfo: {}
		}
	},
	actions: {
		setUserInfo(userInfo) {
			this.userInfo = userInfo;
		},
		clear() {
			this.userInfo = {};
		},
		async loadUser() {
			const userInfo = await http({ url: '/user/self' });
			this.setUserInfo(userInfo);
			if (userInfo.deleted || userInfo.isBanned) {
				throw new Error("账户已注销或被封禁")
			}
		}
	}
})