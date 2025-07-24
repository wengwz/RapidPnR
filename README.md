# RapidPnR

## Environment Setup
RapidPnR is developed and evaluated under Ubunt 22.04 and its execution requires the following tools:
- [Vivado 2023.2](https://www.xilinx.com/support/download.html): Detailed installation guide sees the following [link](https://www.xilinx.com/support/download.html)

- [TritonPart](https://github.com/ABKGroup/TritonPart): It's recommended to install TritonPart through our prepared docker image. You can find the installation guide of docker via this [link](https://docs.docker.com/engine/install/ubuntu/). After docker is installed successfully, execute the following command to get our TritonPart image:
```bash
docker pull crpi-vxps6h1znknsd4n1.cn-hangzhou.personal.cr.aliyuncs.com/wengwz/openroad:bin
```

- Java (1.8 minimum, 11 or later recommended): You can install Java development tool kit using the following command:

```bash
sudo apt install openjdk-11-jdk
```

## How to build
You can build RapidPnR following these commands:
```bash
git clone https://github.com/wengwz/RapidPnR.git
cd RapidPnR
./gradlew compileJava
```