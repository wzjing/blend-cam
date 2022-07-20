# BlendCamera
> 基于OpenGL和CameraX实现的自定义相机

## 原理

通过OpenGL+TextureView搭建一个OpenGL环境，创建一个Texture ID，然后通过Surface传给CameraX的Preview接受数据，在FrameAnalyzer触发每一帧的回调时，触发OpenGL的绘制操作