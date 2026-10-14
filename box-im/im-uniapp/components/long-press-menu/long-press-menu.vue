<template>
	<view class="long-press-menu none-pointer-events">
		<view class="menu-slot" @touchmove="onTouchMove" @touchend="onTouchEnd">
			<slot></slot>
		</view>
		<view v-if="isShowMenu" class="menu-mask" @touchstart="close()" @click.stop="close()" @contextmenu.prevent="">
		</view>
		<view v-if="isShowMenu" class="menu" :style="menuStyle">
			<view class="menu-item" v-for="(item) in items" :key="item.key" @click.prevent="onSelectMenu(item)"
				:class="{ 'danger': item.danger }">
				<text v-if="item.icon" class="icon iconfont" :class="item.icon"></text>
				<text class="menu-text">{{ item.name }}</text>
			</view>
		</view>
	</view>
</template>

<script>
export default {
	name: "long-press-menu",
	data() {
		return {
			isShowMenu: false,
			isTouchMove: false,
			items: [],
			menuStyle: "",
			menuTouch: {}
		}
	},
	methods: {
		open(e, items) {
			if (this.isTouchMove) {
				// 屏幕移动时不弹出
				return;
			}
			this.items = items;
			uni.getSystemInfo({
				success: (res) => {
					let touches = e.touches[0];
					let style = "";
					/* 因 非H5端不兼容 style 属性绑定 Object ，所以拼接字符 */
					if (touches.clientY > (res.windowHeight / 2)) {
						style = `bottom:${res.windowHeight - touches.clientY + 20}px;`;
					} else {
						style = `top:${touches.clientY + 20}px;`;
					}
					if (touches.clientX > (res.windowWidth / 2)) {
						style += `right:${res.windowWidth - touches.clientX}px;`;
					} else {
						style += `left:${touches.clientX}px;`;
					}
					this.menuStyle = style;
					//
					this.$nextTick(() => {
						this.isShowMenu = true;
					});
					this.menuTouch = touches
				}
			})
		},
		close() {
			this.isShowMenu = false;
		},
		onTouchMove(e) {
			this.isTouchMove = true;
			let touches = e.touches[0];
			// 屏幕拖动大于50px时，取消菜单
			if (Math.abs(touches.clientX - this.menuTouch.clientX) > 50 ||
				Math.abs(touches.clientY - this.menuTouch.clientY) > 50) {
				this.close();
			}
		},
		onTouchEnd() {
			this.isTouchMove = false;
		},
		onSelectMenu(item) {
			this.$emit("select", item);
			this.isShowMenu = false;
		}
	}
}
</script>

<style lang="scss">
.long-press-menu {

	.menu-mask {
		position: fixed;
		left: 0;
		top: 0;
		right: 0;
		bottom: 0;
		width: 100%;
		height: 100%;
		z-index: 999;

	}

	.menu {
		position: fixed;
		border-radius: 16rpx;
		overflow: hidden;
		background: white;
		z-index: 1000;
		box-shadow: 0 8rpx 32rpx rgba(0, 0, 0, 0.12);
		border: 1rpx solid rgba(255, 255, 255, 0.2);
		padding: 8rpx 0;
		min-width: 200rpx;

		.menu-item {
			height: 88rpx;
			display: flex;
			padding: 0 32rpx;
			align-items: center;
			font-weight: 500;
			transition: all 0.2s ease;
			position: relative;
			text-align: center;
			color: var(--im-text-color);

			&:hover {
				background: $im-bg-active;
				color: $im-color-primary;
			}

			&:active {
				background: $im-bg-active-dark;
				transform: scale(0.98);
			}

			&.danger {
				color: $im-color-danger;

				&:hover {
					background: rgba(245, 108, 108, 0.1);
					color: $im-color-danger;
				}

				&:active {
					background: rgba(245, 108, 108, 0.2);
					transform: scale(0.98);
				}
			}

			.icon {
				margin-right: 20rpx;
				font-size: 36rpx;
				width: 36rpx;
				text-align: center;
			}

			.menu-text {
				font-size: 30rpx;
				flex: 1;
			}
		}
	}
}
</style>