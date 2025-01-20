import matplotlib.pyplot as plt
#plt.figure(figsize=(6, 6))
labels = ['Others', 'Netlist Abstraction', 'Design Partition', 'Parallel PnR & Merge']

size1 = [30, 60, 150, 1567]
# color1 = ['#9E480E', '#636363', '#997300','#255E91']
color1 = ['#9E480E','#4472C4' , "#5B9BD5","#ED7D31"]

size2 = [35, 60, 202, 612, 692]
color2 = ["#4472C4","#ED7D31", "#FFC000", "#5B9BD5", "#70AD47"]
# color2 = ["#4472C4","#ED7D31", "#FFC000", "#5B9BD5", "#70AD47", "#264478"]

plt.pie(size1, colors=color1, startangle=140, wedgeprops={'width': 0.4, "edgecolor":"white"})


plt.savefig('breakdown1.png', dpi=300, bbox_inches='tight', transparent=True)
plt.show()
