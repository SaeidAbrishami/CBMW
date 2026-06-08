import os
import shutil
import xml.etree.ElementTree as ET
import numpy as np
from pathlib import Path

# Define the workflow folder
workflows_folder = os.path.dirname(os.path.abspath(__file__))

# Find all XML files in the workflows/1 folder (not in subdirectories)
xml_files = [f for f in os.listdir(workflows_folder) if f.endswith('.xml') and f.count("_") == 1 and os.path.isfile(os.path.join(workflows_folder, f))]

print(f"Found {len(xml_files)} XML files in {workflows_folder}")

for xml_file in xml_files:
    base_name = os.path.splitext(xml_file)[0]  # Remove .xml extension
    xml_path = os.path.join(workflows_folder, xml_file)
    
    print(f"\nProcessing: {xml_file}")
    
    # Create 20 duplicates
    for i in range(21, 26):
        # Duplicate XML file
        duplicated_xml_name = f"{base_name}_{i}.xml"
        duplicated_xml_path = os.path.join(workflows_folder, duplicated_xml_name)
        shutil.copy2(xml_path, duplicated_xml_path)
        
        # Parse XML and extract runtime values from <job> tags
        try:
            tree = ET.parse(duplicated_xml_path)
            root = tree.getroot()
            
            # Define namespaces
            namespaces = {'': 'http://pegasus.isi.edu/schema/DAX'}
            
            # Find all job elements (handle both with and without namespace)
            jobs = root.findall('.//job', namespaces)
            if not jobs:
                jobs = root.findall('.//job')
            
            runtimes = []
            for job in jobs:
                runtime_str = job.get('runtime')
                if runtime_str:
                    try:
                        runtime = float(runtime_str)
                        runtimes.append(runtime)
                    except ValueError:
                        pass
            
            # Generate random numbers based on normal distribution
            random_values = []
            for runtime in runtimes:
                mean = runtime
                std_dev = runtime / 10.0
                random_val = np.random.normal(mean, std_dev)
                random_values.append(random_val)
            
            # Save to txt file
            txt_filename = f"{base_name}_{i}.txt"
            txt_path = os.path.join(workflows_folder, txt_filename)
            
            with open(txt_path, 'w') as f:
                for val in random_values:
                    f.write(f"{val}\n")
            
            print(f"  Created: {duplicated_xml_name} ({len(runtimes)} jobs) -> {txt_filename} ({len(random_values)} values)")
            
        except Exception as e:
            print(f"  Error processing {duplicated_xml_name}: {e}")

print("\nDone! All files have been created.")
