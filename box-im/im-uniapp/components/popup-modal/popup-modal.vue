<template>
	<uni-popup ref="popup" type="center">
		<view class="popup-modal">
			<view class="title">{{title}}</view>
			<view class="middle">
				<view v-if="content" class="content">{{content}}</view>
				<slot></slot>
			</view>
			<view class="bottom">
				<view v-if="showCancel" class="cancel" @click="onCancel">{{cancelText}}</view>
				<view class="confirm" @click="onConfirm">{{confirmText}}</view>
			</view>
		</view>
	</uni-popup>
</template>

<script>
export default {
	name: "pop-modal",
	props: {
		autoClose: {
			type: Boolean,
			default: true
		}
	},
	data() {
		return {
			title: '',
			content: '',
			confirmText: '',
			cancelText: '',
			showCancel: true,
			success: null,
			cancel: null
		};
	},
	methods: {
		open(param) {
			this.title = param.title;
			this.content = param.content;
			this.confirmText = param.hasOwnProperty('confirmText') ? param.confirmText : '确定';
			this.cancelText = param.hasOwnProperty('cancelText') ? param.cancelText : '取消';
			this.showCancel = param.hasOwnProperty('showCancel') ? param.showCancel : true;
			this.success = param.success;
			this.cancel = param.cancel;
			this.$refs.popup.open();
		},
		close() {
			this.$refs.popup.close();
		},
		onCancel() {
			this.cancel && this.cancel();
			this.close();
		},
		onConfirm() {
			this.success && this.success();
			if (this.autoClose) {
				this.close();
			}
		}
	}
}
</script>

<style lang="scss" scoped>
.popup-modal {
	position: relative;
	background-color: white;
	border-radius: 15rpx;
	overflow: hidden;
	margin: 0 80rpx;
	padding: 15rpx;

	.title {
		text-align: center;
		line-height: 60rpx;
		font-size: $im-font-size;
		font-weight: 600;
	}

	.middle {
		padding: 20rpx 30rpx;
		min-width: 400rpx;

		.content {
			font-size: $im-font-size-small;
			margin-bottom: 15rpx;
		}
	}

	.bottom {
		display: flex;
		justify-content: space-around;
		line-height: 80rpx;
		border-top: 1rpx solid #ccc;

		.cancel {
			font-size: $im-font-size;
			color: $im-color-danger;
			width: 50%;
			border-right: 1px solid #ccc;
			text-align: center;

		}

		.confirm {
			font-size: $im-font-size;
			color: $im-color-primary;
			width: 50%;
			text-align: center;
		}
	}
}
</style>