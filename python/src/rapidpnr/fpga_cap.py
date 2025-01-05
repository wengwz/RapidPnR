import matplotlib.pyplot as plt
import numpy as np

# https://en.wikipedia.org/wiki/Virtex_(FPGA)#cite_note-Xilinx-Inc-Jun-2004-10-K-15
year = [1999, 2001, 2004, 2006, 2009, 2010, 2011, 2014, 2016]
names = ["E-Series", "II-Series", "4-Series", "5-Series", "6-Series", "7-Series", "7-Series(3D)", "Ultrascale", "Ultrascale+"]
tech_nodes = ["180nm", "150nm", "90nm", "65nm", "40nm", "28nm", "28nm", "20nm", "16nm"]
# lut_count = [73008 * pow(2, 4), 99214 * pow(2, 4), 178176 * pow(2, 4), 207360 * pow(2, 6), 474240 * pow(2, 6), 612000 * pow(2, 6), 1221600 * pow(2, 6), 2532960 * pow(2, 6), 1728000 * pow(2, 6)]
lut_count = [73008, 99214, 178176, 207360 * 4, 474240 * 4, 612000 * 4, 1221600 * 4, 2532960 * 4, 1728000 * 4]

ff_count = [73008, 99216, 178176, 207360, 948480, 1224000, 2443200, 5065920, 3456000]
bram_cap = [ 832, 7992,   9936,  18576,  38304,   54000,   67680,  136089, 465408] # Kb
io_count = [ 804, 1164,    960,   1200,   1200,    1000,    1200,  1404,     1976]
# II-Series: https://docs.amd.com/v/u/en-US/ds083
# 4-Series: https://docs.amd.com/v/u/en-US/ds112

# 设置图形大小
plt.figure(figsize=(9, 6))

# 创建第一个Y轴 (lut_count 和 ff_count)
fig, ax1 = plt.subplots(figsize=(9, 6))
ax1.grid(True)
ax1.set_xticks(year, font_size=14)
ax1.set_xlabel('Year', fontsize=15)
ax1.set_ylabel('FF or LUT4 Count', fontsize=15)

# 绘制 LUT Count
line1, = ax1.plot(year, lut_count, 'b-o', label='LUT4 Count', markerfacecolor='blue', markersize=10)
# ax1.tick_params(axis='y', labelcolor='blue')

# 绘制 FF Count
line2, = ax1.plot(year, ff_count, 'g-s', label='FF Count', markerfacecolor='green', markersize=10)
# ax1.tick_params(axis='y', labelcolor='green')
ax1.tick_params(axis='y')

# 创建第二个Y轴 (bram_cap 和 io_count)
ax2 = ax1.twinx()
ax2.set_ylabel('BRAM Capacity(Kb)', fontsize=15)

# 绘制 BRAM Capacity
line3, = ax2.plot(year, bram_cap, 'r-^', label='BRAM Capacity (Kb)', markerfacecolor='red', markersize=10)
ax2.tick_params(axis='y')


# 设置标题
plt.title('Evolution of Xilinx Vertex FPGAs', fontsize=16)

# 设置图例
lines = [line1, line2, line3]
plt.legend(lines, [line.get_label() for line in lines], fontsize=15)

# annotation
for i in range(8):
    node = tech_nodes[i]
    name = names[i]
    x_offset = 0
    y0_offset = 25
    y1_offset = 10
    if i == 4:
        x_offset -= 6
        y0_offset -= 3
        y1_offset -= 3
    elif i == 1:
        x_offset += 5
    elif i == 5:
        x_offset += 5
        y0_offset += 5
        y1_offset += 5
    elif i == 7:
        x_offset -= 8
        y0_offset -= 5
        y1_offset -= 5

        
    ax1.annotate(f'{name}', (year[i], lut_count[i]), fontsize=12.5, fontweight='bold', textcoords="offset points", xytext=(x_offset, y0_offset), ha='center')
    ax1.annotate(f'{node}', (year[i], lut_count[i]), fontsize=12.5, textcoords="offset points", xytext=(x_offset, y1_offset), ha='center')

ax2.annotate(f'{names[8]}', (year[8], bram_cap[8]), fontsize=12.5, fontweight='bold', textcoords="offset points", xytext=(15, 18), ha='center')
ax2.annotate(f'{tech_nodes[8]}', (year[8], bram_cap[8]), fontsize=12.5, textcoords="offset points", xytext=(15, 5), ha='center')

# 显示图表
fig.tight_layout()
plt.show()

